# HTTP 接口契约

状态：依据 Q58 已确认设计整理的可实施规格 v1；尚未生成应用端点或 OpenAPI 文件。既有文档中的路径示意以本文 `/api/v1` 和 `/internal/v1` 命名为准。字段校验、鉴权、幂等和结果发布语义为必须实现的契约，模型／媒体参数通过配置与集成验证固定。

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

深度检索由 Agent 的受控 CONTENT_REFINE 工具在预算内请求；超预算澄清，不开放任意模型参数／全素材高密度调用给浏览器。普通查询 API 也限制查询长度、返回量与频率。

SearchHit 基线：assetId、retrievalUnitId、evidenceIds、sourceKind、text、observedAtMs 或 startMs／endMs、temporalPrecision、candidateWindow、contextAvailable、coverage。`candidateWindow` 不能代替观察时间；排名分数不是事实置信度，返回阶段缺口供 Agent 解释未命中。播放授权单独按需获取，不把大量临时 URL 塞入模型上下文。

## 制作会话与消息

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
