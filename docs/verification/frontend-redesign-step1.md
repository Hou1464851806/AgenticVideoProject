# 前端重构步骤 1：实屏、契约与测试基线

日期：2026-10-16（Asia/Shanghai）。依据：[设计](../frontend-redesign-design.md)、[实施计划步骤 1](../frontend-redesign-implementation-plan.md#步骤-1-建立实屏和契约基线)、[AGENTS.md](../../AGENTS.md)。观察代码起点：`82bf48de3e61026a3b13b49c439e8f7cc87fe22c`。本步不修改业务逻辑，不部署、不重建服务、不上传素材、不调用模型、不提交预览或导出。

## 验收结论与环境

| 标准 | 结果 | 证据与边界 |
| --- | --- | --- |
| R1-01 三视口实屏 | 通过 | 下表 24 张命名截图；真实登录工作区 15 张，受控生命周期／分析状态 9 张，明确分开 |
| R1-02 字段追溯与最小决定 | 通过 | 字段映射及缺口表；接口已有能力优先复用，未增加后端字段 |
| R1-03 有效测试清单 | 通过 | 已核对全部现有 `*.spec.ts` 的夹具、付费、Compose 和协议依赖；混合文件按用例区分 |
| R1-04 状态准备 | 通过 | 当前草稿、历史预览、多输出、封存只读、UNKNOWN、预算耗尽、部分失败已有确定性数据；其余状态列出准备入口与未覆盖项 |

真实入口为 `http://localhost:8080`，现有 Compose 的 `business-api`、PostgreSQL、RabbitMQ、Silo、Qdrant、`agent-worker` 已运行。复用 `.scratch/asr-fixture/preview-seed.json` 的已有测试账号和已分析项目，使用正常登录表单；不调用 `renderSeed`，缓存失效即失败，不以重新上传／分析回退。不保存账号密码、Cookie、签名 URL 或原始回执到本记录。

本次部署入口与本地 `frontend/dist/index.html` 引用相同的 `index-D4zLAFD8.js` 和 `index-B4ONWQUS.css`。受控夹具还在 `http://127.0.0.1:5173` 的当前源代码运行。真实登录不能直接使用该 Vite 地址：后端 `BrowserRequestProtection` 校验 Origin，部署允许 `http://localhost:8080`；首次在 5173 采集登录被拒绝，改用部署入口后通过，未更改安全配置。

新增的[基线浏览器文件](../../frontend/e2e/frontend-redesign-baseline.spec.ts)仅用于观察现行公共 HTTP 边界与生成证据，不实施未来 UI 行为，也不新增通用测试框架。真实分支必须显式启用；受控分支拦截全部业务 API，未定义接口和任何业务写请求都会令用例失败。SSE 为有限心跳响应，截图可能显示“连接暂时中断，正在恢复”，不能据此宣称真实 SSE 恢复通过。

## 实屏目录与发现

截图使用指定浏览器视口并保存完整页面，因此 PNG 高度可能大于视口高度；页面横向溢出时宽度也会超过视口。本轮 768 宽工作区截图达 973px，390 宽结果截图达 518px，属于已发现的问题，不是把视口设成了图片宽度。每个链接均为本轮生成的证据。

| 场景与数据来源 | 1366×768 | 768×1024 | 390×844 |
| --- | --- | --- | --- |
| 真实：未登录 | [登录](frontend-redesign-step1/real-login-1366x768.png) | [登录](frontend-redesign-step1/real-login-768x1024.png) | [登录](frontend-redesign-step1/real-login-390x844.png) |
| 真实：已登录项目列表 | [项目](frontend-redesign-step1/real-projects-1366x768.png) | [项目](frontend-redesign-step1/real-projects-768x1024.png) | [项目](frontend-redesign-step1/real-projects-390x844.png) |
| 真实：素材区域、已有素材 | [素材](frontend-redesign-step1/real-assets-1366x768.png) | [素材](frontend-redesign-step1/real-assets-768x1024.png) | [素材](frontend-redesign-step1/real-assets-390x844.png) |
| 真实：对话区域、恢复已有聊天 | [对话](frontend-redesign-step1/real-conversation-1366x768.png) | [对话](frontend-redesign-step1/real-conversation-768x1024.png) | [对话](frontend-redesign-step1/real-conversation-390x844.png) |
| 真实：播放器与结果区域 | [结果](frontend-redesign-step1/real-results-1366x768.png) | [结果](frontend-redesign-step1/real-results-768x1024.png) | [结果](frontend-redesign-step1/real-results-390x844.png) |
| 受控：当前双输出草稿、历史草稿／预览、UNKNOWN | [当前与历史](frontend-redesign-step1/controlled-draft-history-unknown-1366x768.png) | [当前与历史](frontend-redesign-step1/controlled-draft-history-unknown-768x1024.png) | [当前与历史](frontend-redesign-step1/controlled-draft-history-unknown-390x844.png) |
| 受控：封存只读、预算耗尽 | [只读](frontend-redesign-step1/controlled-readonly-budget-1366x768.png) | [只读](frontend-redesign-step1/controlled-readonly-budget-768x1024.png) | [只读](frontend-redesign-step1/controlled-readonly-budget-390x844.png) |
| 受控：语音成功／画面失败 | [部分失败](frontend-redesign-step1/controlled-partial-analysis-1366x768.png) | [部分失败](frontend-redesign-step1/controlled-partial-analysis-768x1024.png) | [部分失败](frontend-redesign-step1/controlled-partial-analysis-390x844.png) |

真实截图恢复的是已有项目当前默认聊天及其服务端保存的方案浏览位置，没有重新制作，也没有点击模型分析、渲染、删除。真实草稿全生命周期、视频播放推进、下载以及 720p／1080p 音画不是这 15 张图的覆盖范围；新版生命周期的当前字段和操作通过受控夹具及已有 HTTP 边界回归核对，真实媒体留给隔离入口。

现状问题（作为步骤 2～5 的基线，不在本步修复）：

- 1366 桌面和 768 平板仍同时排列三栏；768 不触发移动页签，栏宽不足。手机才出现“素材／对话／播放器与结果”切换。
- 品牌、账户、项目标题、全宽用量按钮占据多层页头；素材操作重复使用全宽绿色按钮，已有素材较多时整页被纵向拉长。
- 上传记录与下方素材操作列表分别显示同一批文件，尚未形成统一条目；列表里同名文件可能是不同对象，后续合并必须核对 assetId。加载期间上传面板还可能先显示“暂无素材”，空状态需按真实加载状态处理。
- 草稿显示 `assetId`、`output_key`，历史草稿及预览显示消息 ID；预览消息和请求信息分开展示。当前与历史身份必须保留，但后续需要换成名称及一张状态卡。
- 本轮未完成键盘导航、焦点、对比度和目标布局无横向溢出验收；这些属于后续改版门槛。不得将基线采集通过理解成现有界面已符合重构标准。

## 字段追溯与最小处理决定

以下路径均为 `/api/v1` 下的公共接口。源码链接是当前字段的依据，不以 mock 证明后端存在该字段。

| 展示对象 | 当前接口、字段 | 依据与处理 |
| --- | --- | --- |
| 账户、登录／注册／退出 | `/auth/me`、`/auth/login`、`/auth/register` 的 `user.id/username`；`/auth/logout` | [AuthenticationController](../../backend/src/main/java/com/agenticvideo/business/auth/AuthenticationController.java)、[App](../../frontend/src/App.vue)；保留会话／CSRF 边界 |
| 项目名、分页、项目身份 | `/projects` 的 `items[].id/name/rowVersion,nextCursor`；`/projects/{id}` | [ProjectController](../../backend/src/main/java/com/agenticvideo/business/project/ProjectController.java)；无封面、更新时间、作品数量，首轮不展示这些推测数据 |
| 上传关联素材 | `/projects/{id}/uploads`、`/uploads/{id}`、`/uploads/{id}/complete` 的 `uploadId,assetId,validationTaskId,validationStatus` | [UploadController](../../backend/src/main/java/com/agenticvideo/business/upload/UploadController.java)、[UploadService.view](../../backend/src/main/java/com/agenticvideo/business/upload/UploadService.java)；只能以 `assetId === asset.id` 合并，不能按文件名或哈希去重 |
| 上传进度、恢复和清理 | 同上传接口的 `filename,status,declaredBytes,confirmedBytes,partSizeBytes,expectedParts,expiresAt,reservedBytes,reservedSlots,cleanupStatus,error`；恢复响应含 `confirmedParts`、写入身份 | [uploads.ts](../../frontend/src/uploads.ts)；复用现有真实字节、分片和写入逻辑，不用媒体处理阶段伪造进度 |
| 素材名称、时长、媒体属性 | `/projects/{id}/assets?includePending=true` 的 `items[].id/filename/validationStatus`；`/assets/{id}` 的 `durationMs,byteSize,mediaInfo,timeMapping` | [UploadService.assets](../../backend/src/main/java/com/agenticvideo/business/upload/UploadService.java)、[AssetController](../../backend/src/main/java/com/agenticvideo/business/asset/AssetController.java)；列表没有时长，详情已有。步骤 3 按需获取详情；没有时长不显示 0 |
| 分析能力、失败原因 | `/assets/{id}` 的 `analysis.stages/errors/mediaRetryable`；`/assets/{id}/evidence` 的 `observations,units,vision,summary` | [AssetController](../../backend/src/main/java/com/agenticvideo/business/asset/AssetController.java)、[AnalysisStatus](../../frontend/src/AnalysisStatus.vue)；分别呈现 MEDIA／ASR／VISION／EMBEDDING／INDEX，不用总成功覆盖局部失败 |
| 高级维护 | `/assets/{id}/search-index` 的 `publishedGeneration,generations`，POST `model,dimension,bm25AvgLength`；`/analysis-stages/{stage}/retry` | [SearchIndexController](../../backend/src/main/java/com/agenticvideo/business/search/SearchIndexController.java)、[AnalysisRecoveryController](../../backend/src/main/java/com/agenticvideo/business/analysis/AnalysisRecoveryController.java)；入口搬迁，不自动重建或重试 |
| 原片播放与定位 | POST `/assets/{id}/playback-access` 的 `assetId,filename,durationMs,url,expiresAt`；前端 `startMs/endMs` | [AssetController](../../backend/src/main/java/com/agenticvideo/business/asset/AssetController.java)、[MediaPlayer](../../frontend/src/MediaPlayer.vue)；保持签名续期、时间定位与片段结束边界 |
| 查询与命中、覆盖 | POST `/projects/{id}/search`：`query,literalPhrase,limit,sourceKinds`；结果 `hits` 的名称、证据、区间／观察时刻，`coverage,semanticAvailable` | [SearchController](../../backend/src/main/java/com/agenticvideo/business/search/SearchController.java)、[SpeechSearch](../../frontend/src/SpeechSearch.vue)；原文／语义及稀疏帧限制沿用 |
| 证据上下文、观察帧 | `/evidence/{id}/context?boundedWindowMs=5000`、POST `/evidence/{id}/frame-access` | [SearchController](../../backend/src/main/java/com/agenticvideo/business/search/SearchController.java)；引用继续绑定原素材，不拿当前所选素材替代 |
| 候选复查与扩大补查 | `/projects/{id}/visual-inspections`、`/visual-inspections/{id}` 的 `status,budget,usage,coverage,observations,coverageWarning,expansionProposal`；proposal／decision 的 `choice` | [InspectionController](../../backend/src/main/java/com/agenticvideo/business/analysis/InspectionController.java)、[InspectionPanel](../../frontend/src/InspectionPanel.vue)；保留明确范围和费用授权 |
| 会话与快照 | `/projects/{id}/conversations` 的 `id,title,assetIds`；`/conversations/{id}/snapshot` 的 `cursor,items,assetIds,latestTask,agent,previews,view` | [ConversationController](../../backend/src/main/java/com/agenticvideo/business/conversation/ConversationController.java)、[ProjectRecovery](../../backend/src/main/java/com/agenticvideo/business/conversation/ProjectRecovery.java)；当前草稿可能在最近 100 条消息之外，必须读 `agent.currentDraft` |
| 消息与本轮引用 | POST `/conversations/{id}/turns` 的 `content,references`；消息 `id,role,content,references` | [ConversationPanel](../../frontend/src/ConversationPanel.vue)；保留 ASSET／EVIDENCE／QUESTION／EDIT_DRAFT／PREVIEW_RESULT／PLAN_REVISION 的身份，不恢复旧 `context.editing` 需求类型表单 |
| 制作条件、澄清、操作资格 | `agent.currentProduction.requirements/frozen/editGeneration/currentDraft`、`pendingQuestions`、`canAcceptDraft/canPreviewDraft` | [ProjectRecovery](../../backend/src/main/java/com/agenticvideo/business/conversation/ProjectRecovery.java)、[AgentRuns](../../backend/src/main/java/com/agenticvideo/business/agentrun/AgentRuns.java)；不能根据任务结束自行放开按钮 |
| 草稿标题、输出与片段 | `agent.currentDraft.id/content/normalizedContent.outputs[]` 的 `title,output_key,durationMs,render,clips,subtitles.cues`；binding 的 `messageId,validationId,editGeneration,draftHash,requirementsHash` | [DraftCard](../../frontend/src/DraftCard.vue)、[EditDraftContracts](../../backend/src/main/java/com/agenticvideo/business/plan/EditDraftContracts.java)；标题取输出 `title`，多输出用主题／输出数摘要，不创建假想的草稿顶层名称字段；片段 `assetId` 映射文件名，重名辅以时间／序号 |
| 草稿预览请求与消息 | POST `/conversations/{id}/drafts/previews` 的 `draft,outputKey,previewProfile`；`previews.items` 的 `requestId,taskId,sourceDraftMessageId,outputKey,status,artifactId,artifactState,error`；消息 PREVIEW_RESULT 的 `id` 为 artifact 身份 | [DraftPreviews](../../backend/src/main/java/com/agenticvideo/business/render/DraftPreviews.java)、[PreviewRequests.read](../../backend/src/main/java/com/agenticvideo/business/render/PreviewRequests.java)；先用 artifactId 连消息与请求，再按 requestId 呈现，不按标题／当前草稿合并不同请求 |
| 确认与方案版本 | POST `/conversations/{id}/drafts/accept` 的完整 binding；`/projects/{id}/plans` 的 `id,title`；`/plans/{id}/revisions` 的 `id,revisionNumber,parentRevisionId`；`/revisions/{id}` 的 `content.outputs,changeSummary` | [DraftAcceptanceController](../../backend/src/main/java/com/agenticvideo/business/agentrun/DraftAcceptanceController.java)、[EditPlanController](../../backend/src/main/java/com/agenticvideo/business/plan/EditPlanController.java)；确认整份草稿，草稿 `output_key` 与保存后 `outputId` 不互换 |
| 正式导出与历史 | `/revisions/{id}/confirmations`；POST `/revisions/{id}/export-requests` 的 `outputIds:[单条],confirmationId,exportProfile,explicitIntent`；GET 同路径的记录含 `requestId,revisionId,outputId,createdAt,status,configuration,artifactId,artifactState,error` | [ExportController](../../backend/src/main/java/com/agenticvideo/business/render/ExportController.java)、[PreviewRequests.read](../../backend/src/main/java/com/agenticvideo/business/render/PreviewRequests.java)、[PlanPanel](../../frontend/src/PlanPanel.vue)；保留绑定版本、输出与 720p／1080p 规格 |
| 产物播放、下载、删除 | `/artifacts/{id}` 的 `state,storedBytes`；POST `/playback-access`、`/download-access` 的 `url`；DELETE 的 `state,removedBytes` | [ExportArtifactController](../../backend/src/main/java/com/agenticvideo/business/render/ExportArtifactController.java)、[ArtifactPlayer](../../frontend/src/ArtifactPlayer.vue)；任务成功和文件可用分别显示，不因播放失败重新渲染 |
| 用量与预警 | `/projects/{id}/usage` 的 `storage` 各类 `storedBytes,reservedBytes,pendingCleanupBytes`，`models` 调用数／估算／未知数／明细，`thresholds,warnings`；PATCH `/usage/thresholds` | [ProjectUsageController](../../backend/src/main/java/com/agenticvideo/business/usage/ProjectUsageController.java)、[UsagePanel](../../frontend/src/UsagePanel.vue)；未知费用不能作零，待清理为已用的一部分，阈值仅提示 |
| 失败与恢复 | HTTP envelope `error.code/message/retryable/details`（并非所有接口齐全）；`latestTask.failure.code/message/recoverable`；媒体记录 `error` | [UploadErrors](../../backend/src/main/java/com/agenticvideo/business/upload/UploadErrors.java)、[AuthenticationErrors](../../backend/src/main/java/com/agenticvideo/business/auth/AuthenticationErrors.java)、[api.ts](../../frontend/src/api.ts)；保留已允许公开原因，不展示全量原始 JSON 或内部路径 |

明确缺口及决定：

1. 后端上传响应已有 `assetId`，但 `uploads.ts` 的 `Upload` 类型未声明，两个展示组件也不传关联；步骤 3 只补该已有字段的类型／窄展示接口，不重写传输核心。
2. 素材列表没有时长，项目没有封面／更新时间，草稿没有通用顶层标题。采用上述已有详情与输出标题；无证据的数据留空，不为美化补模型或缩略图服务。
3. PREVIEW_RESULT 引用没有 requestId；请求提供 artifactId，足以连接。没有匹配请求的历史消息独立保留原来源，不伪造请求、不丢历史。
4. 历史草稿消息未必带结构化详情；已有 `/conversations/{id}/drafts?before=...` 可分页取公开草稿。名称优先用已加载的结构化输出，缺详情时保留“历史草稿”及序号，不展示假标题、不增加恢复编辑。
5. `api.ts` 只向组件暴露错误 message 与 retryable；若后续确需分类，不把完整后端 error／details 倾倒界面。当前先用可公开 message 和现有任务 failure，新增错误分类不作为本轮默认后端改动。

## 页面状态与验收数据准备

“已采集”表示本步截图／断言覆盖；“准备入口”只说明可以如何构造，不表示真实服务已执行过该状态。所有未来受控场景必须按对应真实契约供数，不用模型制造异常。

| 状态 | 无模型费用的准备方式 | 本步覆盖与后续门槛 |
| --- | --- | --- |
| 登录／列表／已有项目三区域 | 显式 real 基线，复用已有账号和素材 | 三视口已采集；空项目／无权限用 `projects.spec.ts` 可用断言，步骤 2 回归 |
| 当前草稿在消息窗口外、多输出 | controlled 基线：`agent.currentDraft` + binding，items 不含当前消息；现有 lifecycle 第二条验证选择输出再整稿确认 | 三视口已采集，HTTP 行为回归通过；不等于真实渲染 |
| 历史草稿与历史预览 | controlled 的旧 EDIT_DRAFT／PREVIEW_RESULT，引用保留原 `sourceDraftMessageId/outputKey` | 三视口已采集；播放续签用 lifecycle 第四条；未点击基线中的历史播放按钮 |
| 修改中／条件变化只读 | 给新一代 currentProduction，旧消息保留；canAccept／canPreview=false；可用 live 场景 A→B 原序列 | 准备入口；截图只覆盖 frozen=true 的只读，修改中不冒充已覆盖 |
| 已封存只读与整体确认 | controlled 切换 frozen=true；lifecycle 第二条确认后禁用并刷新 | 三视口已采集；真实原子版本通过后续 LocalMedia 场景 |
| UNKNOWN、预算耗尽 | `latestTask.failure` 分别 PAID_SUBMISSION_UNKNOWN／BUDGET_EXHAUSTED，task.status=FAILED、recoverable=false；不发送任何模型请求 | 三视口已采集，并断言不出现“重试原需求” |
| 语音成功／画面失败 | `/assets/{id}` stages.ASR=SUCCEEDED、VISION=FAILED，保留错误 | 三视口已采集；仅画面重试入口可见，未实际重试 |
| 无音轨、上传验证中／失败、取消／过期清理 | 受控上传响应的真实 status、validationStatus、cleanupStatus 和字节数；静音本地媒体可复用 `variable-frame-rate.mp4` | 准备入口，未采集这些状态；步骤 3 定向适配，勿运行整文件带入 ASR |
| 空／加载／请求失败与重选文件 | HTTP 延迟、4xx／5xx，已有 uploads/resume 行为；文件重选沿用现有 hash worker | 准备入口，步骤 2～3 补有效公共入口行为，不为纯样式造测试 |
| 待澄清、独立问答、恢复／停止 | live 用例受控 claim→parsed→result，WAITING_INPUT + pendingQuestions；HTTP 夹具给 RUNNING／CANCELLED／EXECUTION_INTERRUPTED | lifecycle 第三条覆盖停止独立性；澄清／恢复实屏未采集，步骤 4 适配 |
| 同请求合并、失效与显式重试 | lifecycle 第四／五条：403→同 artifact 续签、410→UNAVAILABLE，匹配 currentDraft 才能 retry | 现有 HTTP 回归覆盖；三视口卡片合并视觉留步骤 4 |
| 待导出、历史版本、多输出、导出中／停止 | 受控 plans→revisions→confirmations→export-requests，固定 revisionId／outputId | 准备入口；真实媒体复用 LocalMedia，不用 renderSeed 冷建 |
| 720p／1080p、外部丢失、删除中／已删除 | LocalMedia 浏览器场景已编排两种清晰度和外部丢失；受控 artifact.state=DELETE_PENDING／REMOVED | 本步未运行隔离媒体或删除；步骤 4～5、阶段验收再执行 |
| 用量未知、预留、待清理、阈值 | 受控 `/usage` 供四类 storage、unpricedCallCount、estimatedCostCny=null 的单次明细、thresholds/warnings | 准备入口；旧 usage 文件不能直接作默认门槛，步骤 5 适配 |
| 长文本、重名素材、跨项目隔离、视图保留输入 | 同名不同 assetId；超长中文名；两个项目／会话 HTTP 数据；键盘和窄屏交互 | 现状未验收；步骤 2～6 基于上述身份补交互断言 |

## 测试清单与准入决定

核对范围：`frontend/e2e/*.spec.ts` 全部现有文件和新增基线文件，以及其直接导入的 `fixtures/render-seed.ts`。当前 Compose 没有历史 `agent-service`、`media-worker`；M4 原文／引用 + Python Worker 为当前对话协议。表中“历史”不等于所有断言无用，只表示不可原封不动当阶段门槛。

门槛“定向”表示后续按业务选用，涉及 UI 改动再适配选择器；“迁移”表示保留业务断言，改为当前 HTTP 夹具或 LocalMedia；“单列”表示真实模型费用或服务故障实验单独安排。本步仅运行下文明确记录的两个文件。

| 文件／必要用例区分 | 新版／历史、数据边界 | 模型费用风险 | 重建／修改服务 | 本阶段门槛决定 |
| --- | --- | --- | --- | --- |
| frontend-redesign-baseline | 新版；GET HTTP mock + 显式真实已有服务 | 无；real 缓存失效直接失败 | 无 | 步骤 1 门槛；后续按布局更新，不作像素快照测试 |
| conversation-lifecycle | 新版；全 HTTP mock、本地 mp4 字节 | 无 | 无 | 步骤 2／4 默认边界门槛 |
| conversation-lifecycle-live | 新版；隔离 Java／DB／MQ／存储／FFmpeg，受控 Agent 结果 | 无 | Java harness 启动隔离容器，非 Compose 重建 | 步骤 4／5、阶段真实媒体门槛；缺注入时 skip 不算通过 |
| conversation-lifecycle-model | 新版；同隔离环境 + 真实 Python／模型 | 显式付费 | harness 启动隔离容器／Worker | 单列模型验收，禁止默认运行 |
| projects | 当前认证／项目／窄屏；真实 Java | 无，不上传、不发送 turn | 无 | 步骤 2 定向，保留隔离和导航断言 |
| auth | 当前认证；真实 DB／API | 无 | 重建 postgres、business-api | 单列故障恢复；普通样式仅验证登录／退出呈现 |
| uploads | 当前上传；真实服务，第二条丢失响应拦截后透传 | 静音 short-video／VFR 无 ASR；不主动视觉分析 | 无 | 步骤 3 定向，核对实际上传文件后执行 |
| upload-resume（除 expired part URL） | 当前上传；真实服务／S3 请求拦截，Buffer.alloc 构造不可解码文件 | 无（不可解码输入不进入模型分析） | 无 | 步骤 3 暂停、继续、重选、重复分片定向 |
| upload-resume：actually expired part URL | 当前上传；真实服务故障注入 | 无 ASR | 重建 business-api，改 URL TTL | 隔离适配后再作门槛，禁止整文件默认跑 |
| upload-cancel | 当前上传取消／过期；真实服务及授权门控 | 无 ASR（取消前不完成上传／仅创建过期会话） | 重建 business-api，改 TTL／清理参数 | 步骤 3 迁移／隔离执行 |
| upload-limits（前两条） | 上传／空间；真实 API／媒体 | 无 ASR（无效输入／静音文件） | 重建 business-api；第二条启停旧 media-worker | 定向迁移当前 Java 媒体入口 |
| upload-limits：queued preview and export | 历史媒体准备；renderSeed + SQL | 冷缓存 ASR；已有缓存也不能视为隔离 | 重建 business-api、启停旧 media-worker、写 DB | 迁移 LocalMedia／受控空间场景 |
| media：local MQ、undecodable、hash、over-duration | 当前原片链路；真实 Java／存储，hash 条拦截输入 | 无（静音或无效视频） | 无 | 步骤 3 定向播放／验证失败／清理 |
| media：variable frame rate and audio offset | 当前媒体时间映射；真实上传 | audio-offset 有音轨，自动分析可能付费 | 无 | 迁移无模型媒体隔离入口或单列 |
| analysis：verified silent video | 当前静音能力状态；真实服务 | 无 ASR，NOT_APPLICABLE | 无 | 步骤 3 定向，不运行整 analysis 文件 |
| analysis：human-transcribed / lost real provider query / uploaded Chinese speech | 真实 ASR；lost query 条代理到真实供应商 | 付费 | lost query 条重建旧 agent-service；发布 MQ 也依赖旧消费者 | 单列模型／故障实验 |
| analysis-credentials | 历史分析执行环境；真实媒体，清空凭据 | 注入期间无模型；恢复共享服务配置 | 重建旧 agent-service | 迁移当前受控 UNVERIFIED 场景 |
| analysis-provider-failure | 真实 ASR 代理，损坏供应商输入 | 付费／已提交调用不能作零 | 重建旧 agent-service | 单列 |
| analysis-recovery | 真实供应商／业务代理、租约故障 | 付费 ASR | kill／重建旧 agent-service | 单列，保留防重复扣费断言 |
| analysis-submit-uncertain | 丢失真实提交回执 | 付费且费用未知 | 重建旧 agent-service | 迁移 HTTP UNKNOWN 呈现；真实故障单列 |
| analysis-upload-retry | 上传模型前失败代理到真实供应商 | 最终付费 ASR | 重建旧 agent-service | 单列 |
| search | 当前查询；真实服务／已分析缓存，冷缓存上传讲话素材 | 冷建 ASR，首条也可能等待真实分析 | 无 | 迁移受控 coverage／原文／定位场景；真实检索单列 |
| semantic-search | 当前语义检索；renderSeed + 真实索引／查询向量 | 冷建 ASR、查询 Embedding | 无 | 单列；前端语义筛选用受控数据 |
| vision | 当前画面与文字查询；真实 VLM／缓存；最后一条真实 DeepSeek | VLM、ASR／Embedding、最后一条 Agent | 无 | 视觉呈现迁移受控；模型单列 |
| inspection | 当前补查授权；真实 VLM、顺序依赖 inspection-seed | VLM／可能 Embedding | 无 | 步骤 3 用受控预算／proposal 适配，真实模型单列 |
| stage-recovery | 历史执行环境；renderSeed、真实 Qdrant／供应商及代理 | 冷建 ASR；恢复／补视觉仍可能付费 | 重建旧 agent-service／media-worker | 迁移部分失败、阶段重试，不默认整跑 |
| conversations | 混合历史会话表单、context 和当前原片定位；beforeEach 可冷建 ASR | ASR + 多条真实 Agent | 无；一条真实签名响应重放 | 迁移有效引用／定位断言；旧表单退出门槛 |
| conversation-recovery | 混合旧执行／SSE；真实服务、stream reset 拦截、批量草稿受控模型代理 | 第一条无模型费用；第二条真实 Agent 付费；第三条受控模型无费用 | 批量草稿条重建旧 agent-service | 当前恢复用 lifecycle／LocalMedia；保留 stream reset 语义 |
| conversation-controls | 旧重试费用确认 UI／模型代理 | 第一条恢复真实 DeepSeek 付费，第二条阻塞代理 | 重建旧 agent-service | 迁移独立停止和合法重试，禁止恢复强制 UNKNOWN 重试 |
| conversation-tool-feedback | 历史供应商工具协议，受控模型 HTTP + 真实 Java | 模型代理无付费；使用静音 VFR | 重建旧 agent-service | 后端工具反馈留专项；UI 仅当前失败呈现 |
| editing-brief | 历史需求类型／简报和旧工具；混合受控／真实 DeepSeek | 第二条真实模型；其余代理 | 第一／三条重建旧 agent-service，第一条还重建 business-api／写 DB | 迁移 pendingQuestions／requirements 当前契约 |
| model-record-limit | 历史 GENERATE_PLAN 表单；受控网关 | 受控 Agent 无付费 | 重建 business-api、旧 agent-service | 将合法字节限制错误呈现迁移当前入口 |
| natural-language | 历史自然语言工具与冻结前保存路线；renderSeed，包含真实 Agent 新聊天／问答 | 即使命中缓存仍有真实 Agent 调用 | 无 | 新聊天空态可迁移；其余模型单列 |
| natural-language-retry | 历史工具和未知费用复选框；受控模型代理 | 无模型费用 | 重建旧 agent-service | 保留幂等语义，迁移当前合法重试，不恢复旧控件 |
| natural-language-production | 历史自然语言预览／确认／导出；renderSeed + 真实 Agent | 冷建 ASR + Agent | 无 | 不作为当前生命周期门槛；由新版 live 取代 |
| natural-language-montage | 历史真实 Agent 混剪／条件澄清 | renderSeed、另传讲话／视觉素材 + Agent，付费 | 调用旧 media-worker 镜像做 FFprobe，无 Compose 重建 | 迁移当前多输出草稿边界；音画单列 |
| montage | 历史 MONTAGE／context.editing；真实 Agent、上传多素材 | ASR／VLM／Agent | 旧 media-worker 镜像 FFmpeg，无 Compose 重建 | 退出当前默认入口，保留时序／媒体断言至隔离验证 |
| plan-modification | 历史冻结版本派生编辑、上下文压缩；renderSeed + SQL + SSE 拦截 | 冷建 ASR + 真实 Agent | 无 Compose 重建，写 DB | 冻结版本派生编辑已不在范围；不适配为新需求 |
| previews | 方案固定版本媒体渲染；renderSeed + SQL／MQ／FFmpeg | 冷建 ASR；渲染本身无模型费 | 第三条改容器 FFmpeg、最后重建 business-api；其余 SQL／MQ 注入 | 当前草稿预览迁移 LocalMedia；保留真实字幕／音画断言作专项 |
| exports | 固定版本导出；renderSeed + SQL／FFmpeg／存储故障 | 冷建 ASR；导出本身无模型费 | 含重建 business-api／直接数据注入 | 步骤 5 迁移 LocalMedia 或受控状态 |
| export-history | 版本历史与恢复；renderSeed + SQL | 冷建 ASR；导出本身无模型费 | 重建 business-api／写 DB | 迁移当前选版本／刷新／文件状态 |
| usage | 当前用量接口与 UI；两条均 renderSeed，第二条核对真实调用／存储 | 第一条冷建 ASR；第二条即使缓存有效仍重新上传并调用 ASR／VISION／Agent／查询向量 | 无 Compose 重建；SQL、旧 agent-service 容器读物理对象 | 步骤 5 新建必要受控数据，阈值首条也不能默认整跑 |
| semantic-agent | 旧模型工具协议；renderSeed + 受控 Agent 代理／真实检索 | 冷建 ASR、第一条真实查询向量；第二条阻塞向量代理 | 重建旧 agent-service；第二条也重建 business-api | 迁移当前 scope 与不越权的业务断言 |
| recovery | 历史媒体消费者恢复；无音轨生成媒体、真实 MQ／FFmpeg | 无模型费用 | kill／启停旧 media-worker、MQ 注入 | 迁移 Java 隔离媒体；不操作当前共享 Compose |

`renderSeed` 的风险不是测试名称决定的：无有效缓存会注册、上传 `labelled-speech.mp4` 并等待 ASR 成功；有效缓存仅减少准备调用，不能消除测试主体的真实模型调用。新基线 real 分支直接读取缓存并正常登录，没有这个回退。禁止为了让旧测试通过而恢复“需求类型”、旧创建会话表单、派生冻结版本编辑或自然语言媒体执行。

## 执行入口与最终验证记录

命令均在 `frontend/` 执行，沿用已安装的 Chrome／Playwright。Vite 受控场景需先启动 `npm run dev -- --host 127.0.0.1`；真实采集需已有部署与可用 preview-seed，缺失时先准备无模型隔离数据，不能调用付费 seed 补齐。

```sh
# 开发时单行为，再跑对应基线文件；real 必须用部署允许的 Origin
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e -- frontend-redesign-baseline.spec.ts -g 'controlled.*1366'
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e -- frontend-redesign-baseline.spec.ts -g controlled
E2E_BASE_URL=http://localhost:8080 E2E_REDESIGN_REAL=1 npm run test:e2e -- frontend-redesign-baseline.spec.ts
# 相邻当前生命周期的 HTTP 边界回归，不匹配 -live/-model 文件
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e -- conversation-lifecycle.spec.ts
npm exec -- vue-tsc --noEmit
```

后续真实媒体入口在仓库根执行（本步未运行）：

```sh
RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp -pl backend '-Dtest=LocalMediaIntegrationTest#unacceptedAndSavedSilentDraftsRenderThroughMqWithTheirOwnSources' -Dlifecycle.browser=true -Dlifecycle.model=false test
```

该 Java harness 启动隔离基础设施和 HTTP 服务，注入 E2E_LIFECYCLE_REAL、SEED、TOKEN、BASE_URL，再运行 live 文件。必须先构建前端。直接运行 live／model 文件缺变量会跳过，不能记录为通过；model=true 是另一个显式付费验收。

| 检查 | 最终结果 |
| --- | --- |
| 受控单场景 1366，再三视口受控文件 | 通过：1 条，再 3 条 |
| 完整基线文件，显式 real + controlled | 通过：6 条，0 失败、0 跳过（补齐方案恢复等待及修正失败任务状态后再次验证） |
| 当前 conversation-lifecycle 五条 HTTP 行为 | 通过：5 条，0 失败、0 跳过 |
| 前端类型检查 | 通过：vue-tsc --noEmit（应用源码；现有 tsconfig 不包含 e2e，夹具由 Playwright 实际执行验证） |
| 文档链接、截图尺寸／数量、git diff --check | 通过：本次四份文档相对文件链接均存在，24 张截图视口名称正确，横向溢出已记录，差异无空白错误 |
| 必要双轴审查 | Standards 无发现；Spec 发现 1 项：UNKNOWN／预算耗尽夹具错误使用 SUCCEEDED，已按 AgentRunResults 映射改为 FAILED，重新验证并采图；修正后复核无遗留 |
| 构建、Java／Python 服务回归、后端打包 | 未运行：本步仅文档／观察夹具，无应用修改，按步骤 1 与 AGENTS 的分层范围执行；阶段全量留步骤 6 |
| LocalMedia live／真实模型／人工音画 | 未运行；不记为通过或跳过 |

排查过程中曾失败：5173 真实登录因 Origin 不受信任；随后补等待会话加载时，手机隐藏面板的普通 role 查询找不到新聊天按钮，已改为 `includeHidden:true` 等待已挂载组件就绪。另一次给生命周期测试加文件名前导 `^` 导致未发现用例，改用计划中的精确文件名后 5 条均执行通过。这些均属采集入口／夹具问题，未修改产品逻辑；最终结果仅以修正后的执行为准。

审查修正：`AgentRunResults` 将 INCOMPLETE／FAILED 映射为任务 FAILED，因此 UNKNOWN／预算耗尽夹具及对应截图统一使用 FAILED。该修正只影响受控基线，补跑单条及完整基线文件；先前的成功标记截图不作为最终证据。
