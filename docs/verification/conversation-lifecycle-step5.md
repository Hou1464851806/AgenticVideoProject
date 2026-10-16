# 对话生命周期步骤 5：草稿预览来源与聊天播放结果

日期：2026-10-16（Asia/Shanghai）。实施基准 `3e2412e598abf4d1374d8530b330d51b7b55fdc8`，当前分支 `main`，起始工作区干净。依据[第 4 步交接](conversation-lifecycle-step4.md)与[完整设计](../conversation-lifecycle-browser-design.md)，本次完成七步顺序中的第 5 步，不是整个阶段交付。

## 实施与边界

- 新增 DraftPreviews 与公共 HTTP 适配，负责当前草稿的预览接纳、单输出选择、持久幂等回执和显式重试。与接受入口共用 AgentRuns.requireCurrentDraft；检查所见消息、validation、编辑代次、两种 hash、当前制作、封存状态与未决修改。沿用项目→会话→制作→任务／产物锁顺序；媒体任务不创建 conversation_turns、不占 Agent 执行资格。
- 使用 Java 持久校验的 normalized_content_json，直接固定片段、字幕、渲染规格、源素材对象与指纹到 PREVIEW_RENDER 输入。只复用 LOW_360 配置、空间预算、Outbox、RabbitMQ、FFmpeg、上传与访问授权，不运行 Agent 或重新分析，不分配永久 outputId／clipId，不创建版本或确认。
- V57 在 render_requests、artifacts、preview_intents 增加互斥草稿／版本来源。草稿请求复合外键固定 conversation／message／production／validation／两种 hash；产物按 request／project／source／output／task／kind 复合归属，发布指针也须属于本请求任务。正式 EXPORT 仍须合法 revision／output／confirmation；历史迁移未改写。升级测试包含迁移前已有版本预览、产物及幂等回执。
- 缓存身份包括同一草稿消息、validation、两种 hash、outputKey、源文件指纹、低清配置与 rendererVersion。相同来源复用运行中任务或可用产物；不同会话／草稿消息不共享任务。已有失败／取消／失效产物通过原 request 显式重试；换一个普通请求键不能绕过。`retry_of` 唯一记录后继，retry(A) 已得到 B 后，即使 B 失败或草稿后来变更，重放仍返回 B。
- 幂等重放先于当前资格检查，只返回已接纳结果。旧草稿修改、切换或冻结后仍可查请求与播放已有产物，不能新建预览或重试。已接纳输入不随制作修改或确认而改写；播放地址过期只重新授权访问。
- 媒体完成沿用 TaskExecution 同一事务：发布产物、任务与 attempt 终态、执行回执，并追加一条带来源引用的聊天消息。`source_task_id` 唯一关联防止重复发布，消息没有短期 URL；SSE 的独立消息 taskId 回退到 source_task_id。共享任务方法在草稿媒体 task 锁之前取得会话锁。快照新增独立预览列表，当前 Agent 状态仍独立。

## 第 6 步接入契约

公共入口均使用原会话／项目鉴权及 CSRF；幂等键沿用 AgentRuns 的 1–120 字符规则。

```text
POST /api/v1/conversations/{conversationId}/drafts/previews
Idempotency-Key: <key>
{
  "draft": {
    "messageId": "...", "validationId": "...", "editGeneration": 1,
    "draftHash": "...", "requirementsHash": "..."
  },
  "outputKey": "out-1",
  "previewProfile": "LOW_360"
}

GET  /api/v1/conversations/{conversationId}/drafts/previews
POST /api/v1/conversations/{conversationId}/drafts/previews/{requestId}/retry
GET  /api/v1/render-requests/{requestId}
POST /api/v1/tasks/{taskId}/cancel
POST /api/v1/artifacts/{artifactId}/playback-access
```

POST 接纳／重试返回 202 与既有 `{data,requestId}` 信封。草稿预览 data 包含 `requestId/taskId/kind/status/configuration/createdAt`，以及 `conversationId/sourceDraftMessageId/productionId/validationId/draftHash/requirementsHash/outputKey`；成功后读取可见 `artifactId`，失败有 `error`。不伪造 revisionId／outputId。普通接纳键重放返回原接纳响应；按 request 查询取得最新状态。重试入口无需新的键，持久后继身份决定重放结果。若后继再次失败，明确对后继 request 重试才产生下一项。

列表与会话快照的 `previews` 均为 `{items:[...]}`，包含所有该会话已接纳草稿预览，不依赖最近 Agent task 或消息窗口。现有 `/internal/v1/previews/**` 继续只处理已保存版本，不作为浏览器草稿入口。

成功消息的 `references` 示例：

```json
[{"type":"PREVIEW_RESULT","id":"<artifactId>","requestId":"<requestId>","sourceDraftMessageId":"<draftMessageId>","outputKey":"out-1"}]
```

前端按 artifact 获取播放授权，并在聊天内播放；按 sourceDraftMessageId 标明来源。请求失败／取消在对应请求卡片展示，按钮结合 Java `agent.canPreviewDraft` 与当前草稿身份；实际接纳／重试由 Java 再检查。前端消息／卡片与方案列表仍属第 6 步；浏览器全链路及阶段全量回归属于后续验收，不将旧表单路径当作本步验收。

## 验证范围与实际记录

测试接缝沿用已确认设计与交接：Java HTTP／项目鉴权和持久事务、PostgreSQL 完整迁移及来源约束、独立媒体消费者的实际 RabbitMQ／FFmpeg／对象存储。先以新增 HTTP 接纳测试得到缺少入口的 404，再完成固定输入实现；随后验证完成、重放、失败、取消、重试及历史只读。媒体完成测试在执行回执提交处注入数据库拒绝，核对产物、任务与聊天消息整体回滚，再重报成功。

开发中修正 SQL 参数数量不匹配与不存在账号的权限测试夹具。首轮实际媒体测试在另一 Maven 进程重编译同一 target 期间出现 EditDraftContracts RecordComponents 读取失败；未单独证实该失败的唯一根因，后续串行运行避免输出目录干扰；单独及扩大媒体回归均通过，原失败不计为通过。收尾边界红测试又复现了 Spring 默认 DTO 解析忽略客户端 output 字段（预期 400，实际 202）；控制器改用既有 AgentRunContracts 严格解析，不改全局 ObjectMapper，并覆盖小数编辑代次的拒绝。

范围依据：本步增加媒体双来源迁移、修改共享产物发布／任务锁和快照，回归覆盖新 Agent 接纳与接受、完整迁移、预览／导出／内部 CLI、任务／会话／鉴权及实际媒体消费者。Python、模型输入与 M2 hash 契约未改变；不因单步功能运行全阶段 Java／Python 全量或后端打包。

| 检查 | 实际结果 |
| --- | --- |
| Java 相关回归（请求边界收尾前） | 15 类 149 项通过，0 失败／错误／跳过；包含完整 V1～V57 迁移和已有媒体数据升级 |
| 实际媒体专项（串行） | 单项通过；随后包含在上述 LocalMedia 全部 6 项中通过 |
| 请求边界收尾后 | 单项红→绿通过；最终 AgentRunsApiIntegrationTest 20 项＋实际草稿媒体 1 项通过，0 失败／错误／跳过 |
| Java 主代码与测试类型检查 | Maven test 编译通过；请求边界收尾后补跑同时重新编译 |
| 文档、链接与差异 | 3 份文档 69 个本地链接可解析；git diff --check 通过 |

实际媒体验证使用受控已有视觉分析记录，由手工新版 Agent HTTP 夹具驱动读取与 Java 校验（未运行 Python Worker 或模型），生成未接受双输出草稿；选择 out-2，经真实 MQ／FFmpeg／S3 生成 360×640、H.264／AAC、约 2 秒 MP4，下载与播放授权可用。新预览不创建版本或确认，重复请求／MQ 投递只产生一次执行回执及一条带原草稿引用的消息。原 CLI 已保存静音竖屏预览与正式版本 640×360 预览、1280×720 导出、取消及恢复均覆盖；媒体自动化不等同人工完整观看试听。

未运行：Python 测试（无 Python 或跨语言工具契约改动）、前端类型／构建及浏览器交互（无前端改动，第 6 步未实施）、Java／Python 全阶段完整非付费回归及后端打包（第 7 步）、真实付费模型与人工完整观看试听。本次无环境条件导致的测试跳过。没有部署业务实例或变更本机业务数据库；迁移均在 Testcontainers 隔离库运行。

复现命令（仓库根目录，顺序执行，避免多个 Maven 进程同时编译同一 target）：

```sh
mvn -q -pl backend '-Dtest=AgentRunsApiIntegrationTest,ConversationLifecycleSchemaIntegrationTest,PreviewApiIntegrationTest,InternalPreviewApiIntegrationTest,ExportApiIntegrationTest,RenderCapacityIntegrationTest,TaskApiIntegrationTest,LocalMediaIntegrationTest,ConversationApiIntegrationTest,ConversationRecoveryApiIntegrationTest,DraftValidationIntegrationTest,ProductionSchemaIntegrationTest,EditPlanApiIntegrationTest,AuthenticationApiIntegrationTest,AuthenticationRateLimitIntegrationTest' test
# HTTP 边界收尾：先单项红→绿，再模块和实际媒体补跑
mvn -q -pl backend -Dtest=AgentRunsApiIntegrationTest#draftPreviewRejectsClientContentAndFractionalGenerationAtHttpBoundary test
mvn -q -pl backend '-Dtest=AgentRunsApiIntegrationTest,LocalMediaIntegrationTest#unacceptedAndSavedSilentDraftsRenderThroughMqWithTheirOwnSources' test
git diff --check
```

## Standards

独立审查无可操作规范违反。两类预览保留各自接纳与 Outbox 写入，来源规则独立；共用当前资格、低清配置与源素材序列化，未引入通用渲染接纳框架。

## Spec

独立审查无已确认实质偏差。固定点为 `3e2412e598abf4d1374d8530b330d51b7b55fdc8`，审查包含 `git diff <固定点>` 和所有新增文件；静态审查与运行验证分开报告。两轴增量复核均无剩余实质问题，包括素材依赖校验、旧内部入口来源限制、精确产物发布／重试约束及严格 HTTP 请求解析。收尾后检查结果以上表为准。
