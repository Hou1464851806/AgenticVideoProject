# HTTP 接口契约

状态：依据 Q58 已确认设计整理的可实施规格 v1；应用按下述逐票实施说明交付，表中其余端点仍是后续设计，尚未生成 OpenAPI 文件。既有文档中的路径示意以本文 `/api/v1` 和 `/internal/v1` 命名为准。字段校验、鉴权、幂等和结果发布语义为必须实现的契约，模型／媒体参数通过配置与集成验证固定。

## 通用协议

- 浏览器仅访问 Spring 业务 API；分片 PUT 例外，使用 Spring 签发 URL 直传 Silo。Python／Worker／基础设施不作为浏览器业务入口。
- 首期实现默认同源前端／API 与 HttpOnly 登录 Cookie，写请求使用 CSRF token 并检查允许的 Origin；Silo 浏览器地址独立配置并正确 CORS，不替换已签 URL 的 host／path。服务间凭证与用户登录分开，后端从会话推导用户身份。
- 请求／响应 JSON 使用 camelCase；UUID 为字符串，媒体时间为整数毫秒，文件大小为字节。成功响应 `{data, requestId}`；分页 `{data:{items,nextCursor},requestId}`，不靠 offset 处理消息和事件恢复。
- 创建上传、发送消息、手动编辑、确认、预览、导出、回答澄清、失败重试等写请求使用 `Idempotency-Key`。键的作用域包括已认证用户、操作与目标；对规范化输入计算哈希，同键同输入返回既有结果，同键异输入报 409。
- 权限边界首先验证项目归属及对象关系；无权限对象对外使用统一 404，登录失效 401。不接受浏览器／模型传来的 ownerUserId 作为授权。
- `202` 表示持久接纳，未表示执行成功；`201` 表示业务资源已创建，上传会话仍可能在初始化。断线后查询返回标识，不重复生成新的幂等键重发同一操作。
- 会话／项目恢复快照中的分页历史可通过明确的 messageId 获取单条草稿或最终正文：GET `/api/v1/messages/{messageId}`，仍验证项目归属；事件中的 contentRef 只能引用该受控资源。
- 乐观更新使用 `expectedRowVersion`，冲突返回当前版本提示重读；方案修改使用不可变 baseRevisionId，不自动替换为最新。

错误格式：

```json
{
  "error": {
    "code": "CONVERSATION_BUSY",
    "message": "当前会话仍在处理上一条需求",
    "retryable": false,
    "details": {"activeTurnId": "<uuid>"}
  },
  "requestId": "<uuid>"
}
```

| HTTP | 常见错误码 | 客户端行为 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 修正字段，不自动重试 |
| 401／403 | AUTH_REQUIRED／CSRF_REJECTED | 恢复登录／请求凭据 |
| 404 | RESOURCE_NOT_FOUND | 重新读取可访问资源 |
| 409 | IDEMPOTENCY_CONFLICT／CONVERSATION_BUSY／PLAN_BUSY／ROW_VERSION_CONFLICT | 展示具体冲突，不隐式排队或改基准 |
| 410 | UPLOAD_EXPIRED／EVENT_CURSOR_EXPIRED／ARTIFACT_GONE | 重新上传／载入快照／重建预览 |
| 413 | FILE_TOO_LARGE | 调整文件或配置 |
| 422 | MEDIA_INVALID／CONSTRAINT_UNSATISFIED／REVISION_UNCONFIRMED | 说明可纠正原因 |
| 429 | RATE_LIMITED／EXECUTION_CAPACITY_LIMIT | 按 retryAfterMs 有限重试 |
| 503 | STORAGE_CAPACITY_LOW／DEPENDENCY_UNAVAILABLE | 暂停新工作并展示原因 |

## 身份与项目

| 方法与路径 | 核心入参／结果 |
| --- | --- |
| GET /api/v1/auth/csrf | 返回 CSRF token／登录状态基线，不返回内部凭证 |
| POST /api/v1/auth/register | username、password；首期个人账号注册，限流和用户名唯一 |
| POST /api/v1/auth/login | username、password；设置登录 Cookie，返回用户摘要 |
| POST /api/v1/auth/logout | 结束登录会话，不默认取消已接纳后台任务 |
| GET /api/v1/auth/me | 当前用户与配置能力 |
| POST /api/v1/projects | name；创建项目 |
| GET /api/v1/projects | 用户项目分页 |
| GET /api/v1/projects/{projectId} | 项目、配置限制和分类存储用量 |
| PATCH /api/v1/projects/{projectId} | name、expectedRowVersion；首期不开放任意底层运行参数写入 |

公开配置返回实际生效的文件／项目字节上限、时长／数量限制、恢复期限和输出规格。全局部署参数属于服务配置；不因界面传入 limit 就绕过服务器上限。

## 上传

| 方法与路径 | 语义 |
| --- | --- |
| POST /api/v1/projects/{projectId}/uploads | filename、sizeBytes、sha256、idempotencyKey、writerId；检查与预留，创建唯一会话 |
| GET /api/v1/projects/{projectId}/uploads | 未完成／最近上传分页，支持恢复队列 |
| GET /api/v1/uploads/{uploadId} | 状态、分片规则、核对后片清单分页、期限和 assetId／validationTaskId |
| POST /api/v1/uploads/{uploadId}/resume | 必填 sizeBytes、sha256、writerId；核对同内容、分页核对实际分片并取得上传执行资格 |
| POST /api/v1/uploads/{uploadId}/part-urls | partNumbers、writerToken；小批量签发，返回每片 URL、expiresAt、必须请求头和字节规则 |
| PUT /api/v1/uploads/{uploadId}/parts/{partNumber} | etag、sizeBytes、writerToken；幂等登记并核对实际分片，不能凭声明认定成功 |
| POST /api/v1/uploads/{uploadId}/pause | 停止新授权并记录暂停；客户端同时停止调度，已签请求可收敛 |
| POST /api/v1/uploads/{uploadId}/complete | writerToken；按服务端预定片数／清单完成，不由客户端选择任意对象 key |
| POST /api/v1/uploads/{uploadId}/cancel | 撤销后续操作资格并清理，返回取消／清理状态 |

创建与分片授权示意（标识为占位，URL 不是真实凭据）：

```json
{
  "data": {
    "uploadId": "<uuid>",
    "status": "UPLOADING",
    "partSizeBytes": 33554432,
    "expectedParts": 24,
    "expiresAt": "<UTC timestamp>",
    "writerToken": "<short-lived token>"
  },
  "requestId": "<uuid>"
}
```

32 MiB 仅用于结构举例，分片尺寸与并发不是已实测最优值。writerToken 保护签发／登记操作，同一会话同时只有一个上传控制者；已经签发的 S3 URL 不因控制者切换立即失效，需要短期限、分片核对与最终哈希验证。客户端只保存会话身份／片记录，不保存整个视频；刷新重新取得 File 后恢复。

完成返回 `202` 和现有 assetId／validationTaskId，或 COMPLETING 状态及查询路径；不会提前宣称 VALID。完成响应丢失使用同键重试／查询，后端恢复对象与会话，返回同一素材。取消返回 CANCEL_REQUESTED／ABORTED；容量释放状态单独返回，残留未清不当作空间已回收。

当前实现每片 8 MiB、浏览器共享两个文件执行槽。创建／恢复返回写入凭证，普通查询与列表不返回凭证。writerId 为页面实例 UUID；刷新保留实例身份，新标签页使用独立身份。有效写入资格持续 330 秒，签发或写入时续期；其他页面在资格有效期间返回 409，到期恢复会轮换 writerToken。分片授权默认 300 秒，资格期限覆盖其最大有效期。恢复返回真实存储核对的 confirmedParts 与 confirmedBytes；客户端只调度缺失分片。

## 素材、分析与检索

| 方法与路径 | 语义 |
| --- | --- |
| GET /api/v1/projects/{projectId}/assets | 默认可选素材分页；明确 includeHidden 时只用于历史管理 |
| GET /api/v1/assets/{assetId} | 媒体信息、验证、语音／视觉及索引阶段、覆盖、隐藏与引用摘要 |
| POST /api/v1/assets/{assetId}/playback-access | 短期播放 URL／期限／支持范围；鉴权并登记使用占用，播放器按需续期 |
| POST /api/v1/assets/{assetId}/hide | 隐藏于可选清单，历史版本仍能引用 |
| POST /api/v1/assets/{assetId}/restore | 恢复可选状态，不重复上传分析 |
| GET /api/v1/assets/{assetId}/references | 返回阻止硬删除的方案版本／证据关系 |
| DELETE /api/v1/assets/{assetId} | 仅无受保护引用时显式删除，冲突返回 REFERENCES_EXIST；对象清理可异步 |
| GET /api/v1/assets/{assetId}/analysis | 版本／阶段与有效索引代次，原始模型响应不默认公开 |
| POST /api/v1/projects/{projectId}/search | query、assetIds、sourceKinds、timeRange、literalPhrase、limit；服务端强制范围并执行混合检索 |
| GET /api/v1/evidence/{evidenceId}/context | boundedWindowMs；原始上下文与观察帧引用，受权限与长度限制 |

当前 #9 已实现 `GET /api/v1/assets/{assetId}/evidence`：只返回当前有效分析版本的 observations、units、navigation summary 和安全的 providerCalls。observations 包含句段及字词的音频时间与原素材时间；模型调用记录包含提供方任务 ID、模型、状态、实际返回用量或明确失败码。不存在权限的素材返回 404。`GET /api/v1/assets/{assetId}` 的 analysis.stages／errors 独立表示媒体、语音、检索单元与索引；无音轨为 NOT_APPLICABLE，缺凭据或提交是否接纳不明确为 UNVERIFIED，明确提供方失败为 FAILED。语音完成尚不表示语义索引就绪。

当前 #10 已实现项目内 `POST /search` 的原文字面通道：query（1–256 字符）、literalPhrase、limit（1–50，默认 20）。响应为 hits、channels=[LITERAL]、semanticAvailable=false、coverage.assets；命中包含源 SHA-256、analysisId、检索单元与原始证据引用，candidateWindow.purpose=RETRIEVAL。当前尚不支持语义查询或其分数。上下文入口限定当前可访问证据，boundedWindowMs 为每侧扩展毫秒（默认 5000，最多 30000），每侧最多五条原始邻句。覆盖信息的 startMs／endMs 是素材范围，processedRanges 才表示已成功分析的音轨时间；范围无法确认时返回空数组并在界面解释。

当前 #11 已实现主题检索：`literalPhrase=false` 使用 Dense、BM25、LITERAL 三路 RRF，返回 channels、semanticAvailable、rankings，以及命中的 rrfScore / channelRanks。字面模式仍只匹配原文；有有效索引时 semanticAvailable=true。coverage.assets 增加 indexStatus。召回前强制账号、项目、当前分析及已发布索引代次；Agent 另加固定素材范围，BM25 的 IDF 语料仍限定当前项目。`POST /api/v1/assets/{assetId}/search-index` 返回 202 和 taskId / generationId，重复活跃构建复用任务；`GET` 返回 publishedGeneration 及最近代次状态。新代次未核验、取消或失效时不能替换可查询 head，语音证据仍可字面查询。assetIds / sourceKinds / timeRange 查询入参尚待对应后续票实现。

深度检索由 Agent 的受控 CONTENT_REFINE 工具在预算内请求；超预算澄清，不开放任意模型参数／全素材高密度调用给浏览器。普通查询 API 也限制查询长度、返回量与频率。

SearchHit 基线：assetId、retrievalUnitId、evidenceIds、sourceKind、text、observedAtMs 或 startMs／endMs、temporalPrecision、candidateWindow、contextAvailable、coverage。`candidateWindow` 不能代替观察时间；排名分数不是事实置信度，返回阶段缺口供 Agent 解释未命中。播放授权单独按需获取，不把大量临时 URL 塞入模型上下文。

## 制作会话与消息

当前 #15 已接入创建／列出会话、读取公开消息、接纳查询轮次及浏览器操作。创建参数为 `title`、`assetIds`（最多 100 个本项目有效素材，省略表示空范围）；会话范围创建后固定。轮次 POST 必须带 `Idempotency-Key`，支持 `content`、ASSET／EVIDENCE `references`、可选 `context.assetIds`，后者只能收窄会话范围并在接纳时冻结。纯查询当前不接受方案／输出上下文。消息响应包含 `items`、最近 `latestTask`；公开消息含 `id`、`messageSeq`、`role`、`content`、验证后的 `references`。后续快照／SSE、澄清及方案能力仍按对应任务实施。

当前 #20 的轮次支持 `context.editing={topic,minDurationMs,maxDurationMs,profile,planId?,baseRevisionId?}`，时长为整数毫秒且 1 ≤ min ≤ max ≤ 3,600,000；profile 为 LANDSCAPE_720 或 PORTRAIT_720。省略 planId 由后端新建方案并随接纳身份返回 planId；已有成功版本的方案必须明确 baseRevisionId，接纳后固定，不自动升级为最新。编辑独占权与当前任务／尝试绑定；纯查询不创建版本。

已实现 `GET /projects/{projectId}/plans`（id/title/editingTaskId）、`GET /plans/{planId}/revisions`（id/revisionNumber/parentRevisionId）和 `GET /revisions/{revisionId}`（完整 content）。消息响应增加 `view={planId,revisionId,rowVersion}`，`PATCH /conversations/{conversationId}/view` 接受 planId/revisionId/expectedRowVersion，仅保存本项目已有版本的查看偏好。成功助手消息增加由业务后端分配的 PLAN_REVISION 引用。版本 content 保存一个 output 的渲染规格、有序 sourceInMs/sourceOutMs 半开片段、原声及输出时间轴原文字幕；整句或有实际词级观察的完整观点均可选取。

内部 `POST /internal/v1/tasks/{taskId}/validate-edit` 接受 attemptId/leaseToken/producerKey/proposal，proposal 只允许 title/clips，每个 clip 含 assetId/sourceInMs/sourceOutMs/evidenceIds/reason。证据须由本轮上下文工具核查；每轮最多三份不同候选，重复候选复用持久回执。返回 valid 或 errors，不产生版本。完成入口的 EDIT_PROPOSAL 只接受已经持久校验通过的同一候选，并再次校验当前数据后事务性发布；不能通过直接完成调用绕过修正预算。完成回报幂等性同时核对凭证、producerKey、resultType 和 result。

`AGENT_TURN` 的 message／turn／task／Outbox 在锁定会话的同一事务保存；相同键及规范化 JSON 内容返回同一组标识，同键不同内容或活动轮次冲突返回 409。内部 tool／provider-call／complete 入口均验证当前尝试及有效租约，模型不能传入项目身份覆盖任务范围。业务工具有 12 次上限，模型提交有 6 次上限；已保存成功回执复用，未确认回执停止自动重复付费。发布出处必须出现在本轮授权检索／上下文工具回执中，不能仅凭已知证据 ID 引用。公开消息不包含原始工具协议、reasoning_content 或私有 checkpoint。

| 方法与路径 | 语义 |
| --- | --- |
| POST /api/v1/projects/{projectId}/conversations | title、可选 planId；创建制作会话，后端分配 graph 关联 |
| GET /api/v1/projects/{projectId}/conversations | 会话分页与活动摘要 |
| GET /api/v1/conversations/{conversationId}/snapshot | 分页历史、活动轮次／问题、需求简报、查看偏好、项目事件 cursor；一致快照 |
| GET /api/v1/conversations/{conversationId}/messages | beforeMessageSeq、limit；加载更早历史，不替代首次恢复快照 |
| PATCH /api/v1/conversations/{conversationId}/view | planId、revisionId、outputId、expectedRowVersion；仅查看偏好，不改变任务输入 |
| POST /api/v1/conversations/{conversationId}/turns | content、references、context；接纳用户需求，返回 turnId／messageId／taskId |
| POST /api/v1/questions/{questionId}/answers | content、选择项、context；关联原轮次，核对后恢复或创建关联新任务 |
| POST /api/v1/conversations/{conversationId}/restart-turn | waitingTurnId、content、references、context；显式结束等待并开始新需求 |
| POST /api/v1/turns/{turnId}/retry | 重试失败需求，原失败记录保留；会话繁忙时 409 |

轮次输入示意：

```json
{
  "content": "把第二条视频的第二段换成更完整的例子",
  "references": [{"type": "OUTPUT", "id": "<output-uuid>"}],
  "context": {
    "planId": "<plan-uuid>",
    "baseRevisionId": "<revision-uuid>",
    "outputId": "<output-uuid>",
    "briefVersion": 3,
    "assetIds": ["<asset-uuid>"]
  }
}
```

上下文中显式对象必须可访问、彼此关联，briefVersion 与当前明确约束有冲突则澄清／409，不悄悄选最新版本。用户界面当前查看与发送绑定分别保存。references 类型包括 ASSET／EVIDENCE／OUTPUT／CLIP，引用来源版本明确；无明确“第二条”操作对象时 Agent 澄清。

消息内容为类型化 block：TEXT、EVIDENCE_CARD、QUESTION_CARD、PLAN_RESULT_CARD、TASK_RESULT_CARD，所有卡片身份由后端校验。生成中的文字／卡片只能作草稿展示，版本结果卡仅在成功提交后发布。用户可自然语言修正简报，历史不提供原地编辑／成功回复任意 regenerate 端点。

## 当前已实现的低清预览（#24）

`POST /revisions/{revisionId}/previews` 要求 `Idempotency-Key`（1–200 字符）及 `{outputId,previewProfile:"LOW_360"}`，返回 202 与 `{requestId,revisionId,outputId,taskId,kind,status,configuration,artifactId?}`。同项目预览意图的同键重放返回原接纳回执，同键改变输入返回 409；失败或取消后重新生成使用新键。请求与任务关系不可变，显式重试产生新请求；不同键的相同有效预览仍复用同一任务／产物。

`GET /revisions/{revisionId}/previews` 返回该版本请求历史；`GET /render-requests/{requestId}` 返回固定任务当前状态及失败原因。`POST /artifacts/{artifactId}/playback-access` 校验项目权限及对象存在，返回五分钟播放 URL、durationMs、expiresInSeconds；产物不存在或未发布返回 410，他人产物返回 404。公开接口不返回上传目标。

实际规格为横屏 640×360／竖屏 360×640、30fps、H.264 CRF28／800kbps 上限、AAC 64kbps／48kHz／双声道、按显示比例留边。Worker 使用固定版本内的字幕与片段，不重调 ASR；无音轨片段补静音。缓存包含版本、输出、实际配置、渲染规则及素材校验值；中文字体包版本计入配置。更新渲染规则不会复用旧规则缓存。

预览执行器回报使用 `PREVIEW_RENDERED`，业务端核对当前尝试的服务端上传目标、对象大小、时长与音视频规格；重复完成回报返回同一产物。取消以执行进程退出确认终结；未发布对象仅在所属尝试已终结／确认失效后清理，失败删除由独立扫描重试。

## 方案、预览与正式导出

| 方法与路径 | 语义 |
| --- | --- |
| GET /api/v1/projects/{projectId}/plans | 方案清单及最新版本摘要 |
| GET /api/v1/plans/{planId}/revisions | 按创建序号分页，包含 parentRevisionId／确认／来源 |
| GET /api/v1/revisions/{revisionId} | 完整不可变内容、片段依据和输出 |
| POST /api/v1/plans/{planId}/manual-revisions | baseRevisionId、受支持修改；取得编辑权、校验并追加完整版本 |
| POST /api/v1/revisions/{revisionId}/confirmations | explicitIntent=CONFIRM、来源；幂等确认该版本 |
| POST /api/v1/revisions/{revisionId}/previews | outputId、支持的 previewProfile；复用产物或活动任务 |
| POST /api/v1/revisions/{revisionId}/export-requests | outputIds、confirmationId、exportProfile、explicitIntent=EXPORT；每输出任务，聚合结果 |
| GET /api/v1/render-requests/{requestId} | 输出项、任务、产物、部分成功与失败状态 |
| POST /api/v1/artifacts/{artifactId}/download-access | 可用产物短期授权，已清理预览返回 410 |
| DELETE /api/v1/artifacts/{artifactId} | 用户显式删除产物，正式成片可删除；历史原始素材保护另行核对 |

输出配置使用服务器支持的 profile，禁止任意 codec／shell／下载路径。预览不需要版本确认；正式导出验证该版本确认和本次明确请求，不能从“Agent 说用户已同意”制造授权。自然语言请求须带直接用户消息来源、解析出的准确对象与意图；含歧义时返回澄清卡，不排队渲染。删除引用原始素材返回 409 并列出处理路径，不级联删除方案历史。

## 任务与公开事件

| 方法与路径 | 语义 |
| --- | --- |
| GET /api/v1/tasks/{taskId} | type、status、公开阶段、attemptId、结果、可取消／重试标记 |
| POST /api/v1/tasks/{taskId}/cancel | 取消与完成竞争；已成功返回既有成功状态，不回退 |
| GET /api/v1/projects/{projectId}/snapshot | 项目素材／任务等摘要与一致事件 cursor |
| GET /api/v1/projects/{projectId}/events?after={cursor} | SSE；按授权项目重放，详见 event-protocol.md |
| GET /api/v1/projects/{projectId}/usage | 分类存储及模型实际用量／估算费用 |

任务操作不允许凭 taskId 跨账号调用。未知终态不能被 UI 的 EOF 判断为成功；连接掉线显示重新连接，并读取权威状态。

## 内部执行与 Agent 工具

| 方法与路径 | 语义 |
| --- | --- |
| POST /internal/v1/tasks/{taskId}/claim | executorId、claimRequestId；原子领取并返回固定输入、attemptId、执行凭证、期限 |
| POST /internal/v1/tasks/{taskId}/heartbeat | attemptId、leaseToken、公开进度；仅当前尝试可续期 |
| GET /internal/v1/tasks/{taskId}/control | 核对执行凭证，返回取消／失权，不向浏览器暴露 |
| POST /internal/v1/tasks/{taskId}/edit-claim | 基于固定上下文请求编辑权／分配新建目标；不得任意选择项目或换基准 |
| POST /internal/v1/tasks/{taskId}/events | attemptId、leaseToken、producerSeq、白名单事件；幂等转换为公开事件 |
| POST /internal/v1/tasks/{taskId}/await-input | checkpointRef、questionPayload、questionKey；保存问题并释放执行／编辑占用 |
| POST /internal/v1/tasks/{taskId}/complete | attemptId、leaseToken、resultType、结构化结果；原子验证并发布 |
| POST /internal/v1/tasks/{taskId}/fail | 错误分类与外部状态；后端决定有限重试 |
| POST /internal/v1/tasks/{taskId}/refinements | 指定素材／区间／目标；后端核对预算并调度 CONTENT_REFINE |

受控只读工具包括素材信息、混合检索、上下文、方案读取和已有任务状态，均携带 task／attempt 的受控作用域，不允许工具自声明 owner。模型校验能力和最终 submit 由服务固定节点调用，不能将原始数据库、消息队列或 FFmpeg 开放给模型。

complete 结果类型为 MEDIA_PREPARED、ANALYSIS_RESULT、INDEX_VERIFIED、AGENT_ANSWER、EDIT_PROPOSAL、RENDER_MANIFEST；按任务类型允许列表核对，拒绝跨类型结果。重复完成需在认证执行服务并确认原提交尝试／结果哈希一致后返回既有结果；不同结果／旧尝试的新增提交报冲突。其他已持久报告同样通过 execution_reports 返回既有 receipt，不因为原成功报告已经释放执行权而重新执行副作用。执行服务凭证不授予任意内部数据库查询。

## 验收

接口契约测试覆盖鉴权与项目关联、输入上限、时间／大小单位、幂等同键异输入、忙碌冲突、固定基准、WAITING_INPUT 回答、旧尝试拒绝、确认与导出分离、历史引用删除保护和 SSE 快照恢复。真实对象存储与模型的联调不能由 mock 契约通过替代。

## #25 已实现的单条正式导出契约

- `POST /revisions/{id}/confirmations` 仅接收 `{explicitIntent:"CONFIRM"}`，必填 `Idempotency-Key`；来源由服务器记录，客户端不能伪造用户消息。`GET` 同路径读取该版本确认。
- `GET /revisions/{id}/export-requests` 按项目权限返回该不可变版本的正式导出历史，包含时间、规格、任务与产物身份；刷新或后续任务失败后可再次选择旧成功成片。
- `POST /revisions/{id}/export-requests` 接收 `outputIds`（本票恰好一条）、`confirmationId`、`exportProfile`（`HD_720`／`FULL_HD_1080`）、`explicitIntent:"EXPORT"` 和必填幂等键。返回单个固定输入任务回执；新的意图独立创建任务，同键同输入复用回执。多输出聚合由 #26 实施。
- 正式任务为 `EXPORT_RENDER`，当前尝试获得 `exports/{requestId}/{attemptId}.mp4` 专属上传目标，完成类型 `EXPORT_RENDERED`。受控规格、源校验与进程退出协议复用预览执行器，默认并发为 1。
- `POST /artifacts/{id}/playback-access` 与 `POST /artifacts/{id}/download-access` 在项目鉴权及文件存在校验后签发五分钟 URL；下载带服务器生成的 attachment 文件名，不删除文件。
- `GET /artifacts/{id}` 返回正式产物状态及实际 `storedBytes`；`DELETE` 同路径必须提交 `{explicitIntent:"DELETE"}`。删除意图持久化后才移除对象并核查不存在；存储故障保持 `DELETE_PENDING`，独立扫描重试，不误报释放空间。原素材不在该删除路径内。
- 制作会话的直接 USER 消息支持 `确认当前版本`／`确认V1`，以及另行 `导出当前版本 720p`／`导出V1 1080p`；多条成片时要求 `第1条` 等明确选择。问句、否定、合并确认与导出等不匹配指令只澄清，不进入 Agent 或正式渲染。未明确清晰度时使用 720p。确认和导出保存实际 USER 消息身份，Agent 回复不能制造授权。

## 模型调用记录大小

内部 provider-call 的 data.request／data.response 按紧凑 JSON 的 UTF-8 字节计数，默认上限分别为 16777216／4194304，可通过 MODEL_RECORD_REQUEST_MAX_BYTES／MODEL_RECORD_RESPONSE_MAX_BYTES 覆盖。等于上限允许；超限返回 413、MODEL_RECORD_TOO_LARGE、retryable=false，details 为 recordKind、actualBytes、limitBytes。它是解析后记录字段限制，不是 Token 预算或 HTTP 全请求限制。公开 messages 的 latestTask.failure 仅在该类失败时提供 code/message；其他失败仍使用原有公开提示。
## 上传取消与过期（#7）

`POST /api/v1/uploads/{id}/cancel` 使用项目所有者身份和 CSRF，重复取消返回当前终态；已完成上传返回 409。取消与完成发布串行核对当前状态／操作 token，取消后拒绝续传、签发、登记及完成；旧初始化回执和创建重放不复活终态。

上传查询新增 `declaredBytes`（原文件大小）、`reservedBytes`（当前字节预留）、`reservedSlots`（素材名额）与 `cleanupStatus`。`CANCELLED`／`EXPIRED` 时名额立即释放，字节在实际清理核对后释放；`PENDING` 与 `CLEANED` 分别表示尚未核实清理和已核实无残留。清理完成后仍持续复核迟到存储事实，发现残留会恢复预留及 PENDING。每次清理仅作用于该撤销会话的服务端对象键，真实 Abort multipart／删除未发布对象，操作在数据库事务之外执行，并以持久 lease 核对结果。

`upload.inactivity-seconds` 默认 86400，最后合法活动续期；读取及被拒绝操作不续期。活动完成 lease 保护正在合法完成的会话。`upload.cleanup-settle-seconds` 默认 30，初次释放等待全部已签发 URL／控制操作期限加排空窗口；持续复核弥补更晚完成的存储请求。`upload.cleanup-interval-ms` 默认 10000。Compose 对应 `UPLOAD_INACTIVITY_SECONDS`、`UPLOAD_CLEANUP_SETTLE_SECONDS`、`UPLOAD_CLEANUP_INTERVAL_MS`。

### 上传限额与媒体执行空间

创建上传以服务端配置的文件、项目原始字节及视频数量限额判断，并在同一事务内预留跨项目未来写入预算；分片签发和完成复查当前限额与实际卷容量。拒绝原因通过 `error.message` 明确说明限制及下一步操作。`STORAGE_SPACE_LOW` 表示无法核对卷或容量不足，不能继续写入；超额真实分片撤销会话并进入持久清理，不发布素材。

`POST /internal/v1/tasks/{id}/space-check` 要求 Worker 认证和 `{attemptId, leaseToken}`，仅当前有效 RUNNING 媒体 attempt 可调用。成功返回 `{requiredBytes}`，供 Worker 在开始下载、探测或渲染前复查本地工作区。无效凭证／状态返回 409；卷核对失败或低空间返回 409 `STORAGE_SPACE_LOW`。低空间媒体执行产生 `MEDIA_SPACE_LOW` 失败原因，保留原版本，不能发布输出。
