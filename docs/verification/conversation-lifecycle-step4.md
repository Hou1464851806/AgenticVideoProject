# 对话生命周期步骤 4：单实例 Worker、MQ 交接与恢复

日期：2026-10-16（Asia/Shanghai）。实施基准 `4b534790fbaf92553fce8f4595fbb9875ab8df40`，当前分支 `main`，起始工作区干净。依据[第 3 步交接](conversation-lifecycle-step3.md)与[完整设计](../conversation-lifecycle-browser-design.md)，本次完成七步顺序中的第 4 步，不是整个阶段交付。

## 实施与运行

- Java Outbox 仅将新版 AGENT_TURN 路由到 durable `agent.v1`，配置 `agent.dead.v1`；媒体和分析仍在原队列。原确认发布、pending 再通知与 publisher lease 继续复用，legacy 任务不接入新消费者。
- Python Worker 使用 aio-pika exclusive consumer、prefetch=1 和数据目录的进程锁；每次接纳创建独立 RunToolExecutor。调用 Agent 公共入口，不通过 CLI 子进程，不把 MQ／租约协议加入图节点。任务运行和心跳／公开阶段并发，按 claim／已确认心跳的 leaseExpiresAt 维护本地 monotonic 截止；心跳请求挂起也不能越过该截止继续模型动作。停止或失去租约便取消本地 I/O，确认退出后提交 cancelled；活动回报与取消的 409 竞态重新核对心跳。
- `/tools` 请求携带当前 task／attempt／lease／editGeneration；范围来自 Java 固定绑定。新增 `/receipts` 在同一当前执行资格下返回 Java 持久工具回执的证据身份，Worker 只投影实际公开答复或草稿用到的证据。继续修改以 Java 固定的当前条件为基线：取消前 Java 已接纳而本地尚未应用的条件仍保留；旧 checkpoint 落后或尚未创建时可按新 execution 开始，同 scope 的旧观察可复用，旧代次校验不恢复资格。显式补答仍必须匹配原 wait／execution。问题与草稿身份按 task 确定性生成，不依赖进程随机状态；公开草稿不再提示 CLI save。
- ACK 只在 Java 已持久完成结果／等待／失败／取消，或 claim 返回已有终态时发生。传输失败关闭未 ACK 的通道、稍后重连；取消尚未收束的旧消息等待恢复，不丢弃。格式错误的 MQ 通知拒绝到死信队列。仍有有效执行资格的业务拒绝可持久为非可恢复失败；过期 attempt 不能借此发布。
- AgentRunRecovery 按项目→会话→制作→task／attempt 锁序回收过期租约。RUNNING 旧 attempt 标 EXPIRED，原 task 回到 PENDING 并写 Outbox；CANCEL_REQUESTED 收束为 CANCELLED。固定输入、绑定、原 scope、Python execution／checkpoint 与模型调用序号不变。不复用媒体的本机执行恢复，也不启用旧 complete。
- 可核对且仍有未完成恢复点、没有 SUBMITTING／UNKNOWN 付费回执的本地中断，发布 `FAILED/EXECUTION_INTERRUPTED/recoverable=true`。显式 retry 新建关联 task，保留原失败和用户消息，沿原输入／解析／绑定恢复；重复请求返回同一后继。过期制作、封存制作、无恢复点、预算耗尽及未知付费提交拒绝重试；没有强制重付入口。
- V56 为重试保存同会话 core task 关联。重试发布消息属于新 task，复用校验仍保留原 validation／执行来源：数据库分别约束消息会话、校验制作／hash／代次和来源运行会话，Java 再核对同 core 执行链。没有重建业务表、清理 checkpoint／回执或修改历史迁移、M2 Schema／hash。

Compose 增加 `agent-worker` 及持久卷 `agent_state:/state`，复用原 Python 镜像并覆盖入口为 `python -m agent_service.worker`。配置根 `.env` 的 `DEEPSEEK_API_KEY`；Java／MQ 地址与原内部凭据由 Compose 注入。根目录运行 `docker compose up -d --build agent-worker`。只运行一个实例，保留该卷；CLI 默认入口与独立数据目录继续可用。本次只构建镜像和跑隔离基础设施，不部署本机业务实例或调用真实付费模型。

## 协议与下一步交接

公共发送、草稿接受与取消继续使用第 3 步入口。新版显式重试开放 `POST /api/v1/conversations/{conversationId}/tasks/{taskId}/retry`，body `{}`、`Idempotency-Key`，返回后继 `turnId/messageId/taskId`。不重复追加原用户消息；`acknowledgeUnknownCost=true` 不开放新版强制重付。

claim 保留 `input/inputHash/binding`，重试另带 `execution.input/execution.binding` 指向原核心输入／绑定；后继使用自身 Java 身份发布、原身份运行核心。原理解尚未绑定时，沿已持久理解接纳新绑定，并恢复原核心接纳身份。不能将后继 task 重新 send 成新预算。`POST /receipts` body 为当前 attemptId／leaseToken，返回 camelCase ScopeReceipt 列表；其他内部入口沿用第 3 步协议与 Bearer 认证。

步骤 5 接入 Java 草稿预览来源、固定规范化内容与聊天播放消息；步骤 6 接入前端消息／草稿／预览卡片与方案列表。新版 DRAFT_READY 与问题现已实际由 Worker 发布，但现有前端表单不是本步浏览器验收入口。`canPreviewDraft` 仍只表示草稿业务资格，不能调用旧 revision-only 媒体入口预览未保存草稿。

## 验证范围与实际记录

测试接缝沿用设计／交接：Python 公共入口及 SQLite、模型／HTTP 边界、Java HTTP／权限与持久事务、实际 RabbitMQ 与独立 Python 进程。先以缺少 Worker 公共入口的红测试启动，再验证结果响应丢失和工具租约；Java 租约恢复测试先因缺少恢复组件失败，随后实现并通过。补充受控故障与回归，而非把脚本模型当作真实模型。

开发中出现过测试编译的注解导入冲突／缺失、parsed 字段 snake_case 到 camelCase 转换错误、取消返回码断言错误及新类型声明问题，均修正后重跑。真实 MQ 首轮还揭示尚在 CANCEL_REQUESTED 的旧消息被当作协议错误退出，已改为保持未 ACK 并等待 Java 收束。扩大回归中还发现夹具杀进程后最后一条已接纳 Java 心跳仍可能提交，覆盖刚注入的过期时间，导致首个故障场景超时并级联到后续场景；该轮 MQ 7 项中 6 项失败。已改为在夹具中注入过期并调用真实 recovery，直到旧 attempt 确实 EXPIRED，同时仅取消本场景遗留任务以隔离测试。修正后单独重跑 MQ 全部 7 项通过；随后新增最后确认租约到期时取消挂起心跳／模型，以及取消等待／Java 绑定后尚未创建目标 checkpoint 时继续修改的用例。公共入口先复现旧挂起／代次限制，再修正为 Java 当前条件基线；最终重新运行 Python 和 MQ。以下记录区分原失败运行与最终补跑，不把历史结果算作最终通过。

范围依据：本步修改共享 Outbox 分派、Agent 恢复／重试、新迁移和校验复用，Java 覆盖会话、任务、鉴权、工具、制作／保存／确认与媒体／分析实际消费者；Python 覆盖会话／图恢复／预算／模型回执、Java 工具与 CLI 兼容。前端未改，不运行类型／构建或旧协议浏览器套件。全阶段完整回归与后端打包按计划保留至步骤 7。

最终检查按各次实际执行汇总；本步不将功能顺序视为整个阶段。

| 检查 | 实际结果 |
| --- | --- |
| Java 新 Agent／MQ 专项 | 4 类 31 项通过，0 失败／错误／跳过；MQ 9 项为最终租约／停止边界修正后补跑 |
| Java 相关回归 | Worker 以外 20 类 180 项通过，0 失败／错误／跳过；连同单独补跑的 Worker 9 项合计 21 类 189 项，分次验证 |
| Python 14 个相关文件 | 最终代码 194 项通过，0 失败／跳过 |
| 新 Worker／传输／付费状态存储／续作及测试 7 文件 Pyright | 0 errors、0 warnings |
| Python compileall | agent_service 与 tests 通过 |
| Compose 配置及 Worker 镜像构建 | config --quiet、最终 Worker 镜像构建及容器内导入检查通过 |
| 文档、链接及差异 | 6 份文档 115 个本地链接可解析；git diff --check 通过 |

真实 MQ 专项包含：等待释放后独立讨论再关联补答；实际读证据／Java 校验后发布草稿且无版本；确定模型回执提交后进程退出并换 attempt 恢复；模型 SUBMITTING 中进程退出后停止自动重付；运行中停止；Java 已提交后实际关闭 broker 通道制造 ACK 丢失；显式重试复用原理解回执（SQLite 仅“理解＋决策”两次调用）；Java 已持久校验后本地中断，后继复用原校验并可最终接受版本；取消制作等待后以新执行继续修改，旧问题保持取消；Java 已接纳条件但核心尚未写入制作 checkpoint 时停止，下一轮仍保留该条件并通过校验。全部模型使用显式 ScriptedModel／受控模型边界，没有真实付费调用。API 专项另验证旧 attempt 迟到结果／校验被拒、重复后继、未知付费禁止重试、制作推进后禁止旧重试、会话归属及完整迁移约束。

未运行：Java／Python 全量非付费回归、后端打包、前端类型／构建、新版浏览器验收、真实付费模型和新版草稿媒体／人工观看。原媒体／分析实际消费者回归不等同于新版草稿预览验收。

复现命令（仓库根目录；Java MQ 测试启动独立 Python 进程，须先安装 requirements-dev.lock）：

```sh
mvn -q -pl backend '-Dtest=AgentRun*Test,AgentWorkerIntegrationTest,ConversationLifecycleSchemaIntegrationTest,ConversationApiIntegrationTest,ConversationRecoveryApiIntegrationTest,ModelRecordLimitApiIntegrationTest,ReadToolsIntegrationTest,DraftValidationIntegrationTest,ProductionSchemaIntegrationTest,EditDraftContractsTest,TaskApiIntegrationTest,LocalMediaIntegrationTest,LocalAnalysisIntegrationTest,EditPlanApiIntegrationTest,PreviewApiIntegrationTest,ExportApiIntegrationTest,InternalPreviewApiIntegrationTest,AuthenticationApiIntegrationTest,AuthenticationRateLimitIntegrationTest,ToolJsonTest' test
# 故障夹具修正及最终 Python 租约截止调整后，单独补跑完整 MQ 专项：
mvn -q -pl backend -Dtest=AgentWorkerIntegrationTest test
agent-service/.venv/bin/python -m pytest \
  agent-service/tests/test_worker.py agent-service/tests/test_conversation_runtime.py \
  agent-service/tests/test_agent_scenarios.py agent-service/tests/test_resume_and_limits.py \
  agent-service/tests/test_real_production.py agent-service/tests/test_review_regressions.py \
  agent-service/tests/test_model_protocol.py agent-service/tests/test_java_executor.py \
  agent-service/tests/test_conversation_contracts.py agent-service/tests/test_editing_contracts.py \
  agent-service/tests/test_tool_contracts.py agent-service/tests/test_java_read_tool_contract.py \
  agent-service/tests/test_preview_cli.py agent-service/tests/test_preview_client.py -q
uvx --from pyright pyright --pythonpath agent-service/.venv/bin/python \
  agent-service/agent_service/worker.py agent-service/agent_service/run_transport.py \
  agent-service/agent_service/runtime/persistence.py agent-service/agent_service/runtime/conversation.py \
  agent-service/tests/test_worker.py agent-service/tests/worker_process_fixture.py \
  agent-service/tests/test_conversation_runtime.py
agent-service/.venv/bin/python -m compileall -q agent-service/agent_service agent-service/tests
docker compose config --quiet
docker build -f agent-service/Dockerfile -t agenticvideo-agent-worker:step4 .
```

## Standards

独立初审无硬规范违反，指出 Worker 重复直接读取付费不确定状态的判断性异味；已收敛到 Store.has_uncertain_calls，保持规则在持久层。收尾复核 0 项实质问题。

## Spec

独立初审无确认缺陷，建议覆盖活动发布与取消竞态；已修正并补充专门 HTTP 边界测试。收尾复核 0 项剩余实质偏差。静态审查与上述运行验证分开记录。

两轴固定点均为 `4b534790fbaf92553fce8f4595fbb9875ab8df40`，提交列表为空，读取 `git diff <固定点>` 并逐一读取未跟踪新增文件；未遗漏 Worker／迁移／测试。活动取消竞态、测试夹具隔离及最后确认租约截止和停止后条件／checkpoint 连续性修正均已由两轴增量复核，0 项新增问题。最终代码补跑 Python 194 项、MQ 9 项与类型／镜像检查后不再改业务代码，仅补录文档；其他 Java 模块按原相关回归结果单独记录。
