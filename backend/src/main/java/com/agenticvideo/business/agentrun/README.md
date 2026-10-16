# 新版 Agent 执行与草稿接受

> 管理浏览器消息的固定输入、理解后绑定、执行租约、工具与结果资格，并在用户接受草稿时冻结和确认方案。

## 1. 模块定位与边界

上游 ConversationController 接纳浏览器请求，Python Worker 消费 agent.v1 并访问内部接口；本包维护 Java 权威身份及事务。依赖 conversation、tools、plan、render、task、project 和 PostgreSQL。不在 Java 解释自然语言，不把 Python checkpoint 当业务授权，也不通过接受草稿自动正式导出。

## 2. 快速导航

入口：[AgentRuns.java](AgentRuns.java)。本包随 Java 应用启动，没有独立进程；运行、配置与开发工具见[后端 README](../../../../../../../README.md)。下文命令均从仓库根目录执行。

## 3. 目录与文件职责

| 路径 | 职责 |
| --- | --- |
| [AgentRunContracts.java](AgentRunContracts.java) | 严格 RunInput／ParsedInput／RunResult 与身份／草稿／问题类型 |
| [AgentRuns.java](AgentRuns.java) | 固定消息接纳、关系绑定、重试、当前制作和草稿资格 |
| [AgentRunController.java](AgentRunController.java) | Worker claim／parsed／tools／receipts／result／heartbeat 等传输入口 |
| [AgentRunResults.java](AgentRunResults.java) | 领取固定输入、核对回执和持久发布结果／问题／草稿 |
| [AgentRunTools.java](AgentRunTools.java) | attempt／lease／editGeneration 工具调用准入与归属 |
| [AgentRunLocks.java](AgentRunLocks.java) | 会话／任务事务锁顺序 |
| [AgentRunRecovery.java](AgentRunRecovery.java) | 过期租约撤销与原任务重新通知 |
| [DraftAcceptances.java](DraftAcceptances.java) | 接受当前所见草稿，保存 V1 与确认记录的整体事务 |
| [DraftAcceptanceController.java](DraftAcceptanceController.java) | 浏览器草稿接受路由 |
| [ProductionConditions.java](ProductionConditions.java) | 制作条件增量合并及来源记录 |
| [AgentRunErrors.java](AgentRunErrors.java) | 当前执行错误响应 |

## 4. 架构与内部交互流程

```mermaid
flowchart LR
  U[浏览器消息] --> A[accept 固定 RunInput]
  A --> W[Worker claim / prepare_run]
  W --> B[bind 理解结果和可信身份]
  B --> T[工具及草稿校验]
  T --> R[publish 公开结果]
  R --> D[用户接受草稿]
  D --> V[保存且确认 V1]
```

1. accept 在会话锁下写用户消息、turn、AGENT_TURN、固定 RunInput／inputHash 与 Outbox；历史输入在接纳时固定，不在重投时拼最新聊天。
2. Worker claim 领取 attempt／lease，prepare_run 返回 ParsedInput；bind 验证关系并决定独立问答、新制作、继续制作、关联问题或澄清的可信 scope／thread／execution／编辑代次。
3. 工具经 AgentRunTools 核对当前 attempt、租约与代次，再调用 ReadTools；发布结果只接受本执行的真实成功回执及有效持久草稿校验。
4. 租约过期使旧 attempt 失效，重新通知原执行；显式 retry 只允许可恢复 EXECUTION_INTERRUPTED，不重置预算或强制重付。
5. DraftAcceptances 核对用户看见的 messageId／validationId／hash／editGeneration，调用 ProductionSaves.accept 与 ExportRequests.confirm，在一个事务形成不可变 V1 和确认；正式导出另行请求。

## 5. 公共接口与数据契约

| 入口／契约 | 输入 | 输出／约束 |
| --- | --- | --- |
| `POST /internal/v1/agent-runs/{task}/claim` | executorId、claimRequestId | attempt／lease、固定 inputHash／input／binding |
| `POST .../parsed` | attemptId、leaseToken、parsed | Java 固定执行绑定 |
| `POST .../tools` | attemptId、leaseToken、editGeneration、request | 工具结果 |
| `POST .../receipts`、`.../heartbeat` | attemptId、leaseToken | 执行回执／租约状态 |
| `POST .../result` | attemptId、leaseToken、result | 公开结果持久化投影 |
| `POST .../cancelled`、`.../events` | 执行身份与停止／活动信息 | 停止确认；events 仅允许 activity.updated |
| `POST /api/v1/conversations/{conversation}/drafts/accept` | DraftBinding、Idempotency-Key | planId、revisionId、confirmationId、outputs |

内部端点省略号均沿用 `/internal/v1/agent-runs/{task}`。Java camelCase wire 与 Python snake_case 核心视图区分；schemaVersion=1.0。RunResult 状态包括 ANSWER、DRAFT_READY、WAITING_INPUT、INCOMPLETE、FAILED、CANCELLED，定义和形状以 AgentRunContracts 为准。

## 6. 依赖与配置

INTERNAL_WORKER_TOKEN 是内部 Bearer 凭据；旧 Agent／Worker 开关保持 false。任务通知依赖 TASKS_DISPATCH_ENABLED 和 RabbitMQ。浏览器 Worker 地址与持久卷在 Compose 配置；本包无独立模型或预算环境开关。恢复周期在 AgentRunRecovery 中为 2 秒。

配置生效方式及 Compose 可覆盖范围见[根配置说明](../../../../../../../../README.md#8-配置说明)；Spring 配置来源为[application.properties](../../../../../resources/application.properties)。

## 7. 错误处理与可观测性

过期租约、非当前代次、错属回执或改变同身份内容返回冲突，迟到结果不得发布。公开失败只携带可展示原因；UNKNOWN／预算耗尽不提供重置入口。历史预览与已发布版本保留原身份，不能因新消息恢复旧稿写资格。

## 8. 测试

同名测试包覆盖严格协议、真实数据库约束、接纳／绑定／接受事务与实际 Python 子进程／MQ；后者需要 agent-service/.venv。Python test_conversation_contracts、test_conversation_runtime、test_worker 应配合检查跨语言变更。

```bash
mvn -B -ntp -pl backend -am test -Dtest=AgentRunContractsTest,AgentRunsApiIntegrationTest,AgentWorkerIntegrationTest,ConversationLifecycleSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试源码见[后端测试目录](../../../../../../test/java/com/agenticvideo/business)；集成测试需要 Docker，媒体测试另需宿主 FFmpeg／ffprobe。此处是相关回归命令，文档改动不执行应用测试。

## 9. 扩展与维护

新增对话关系或结果类型需同时改 Java 和 Python 契约、共享样例、状态投影及浏览器恢复；不能只扩枚举。事务锁顺序由 AgentRunLocks 统一维护。新增写工具须保留当前制作和编辑代次授权；用户接受的是完整当前草稿，不是某一输出。

## 10. 相关文档

[根 README](../../../../../../../../README.md) · [后端模块地图](../../../../../../../README.md) · [领域术语](../../../../../../../../GLOSSARY.md) · [conversation](../conversation/README.md) · [tools](../tools/README.md) · [plan](../plan/README.md) · [render](../render/README.md) · [task](../task/README.md)

[conversation-lifecycle-browser-design.md](../../../../../../../../docs/conversation-lifecycle-browser-design.md) · [0019-schedule-agent-through-a-python-worker.md](../../../../../../../../docs/adr/0019-schedule-agent-through-a-python-worker.md) · [conversation-lifecycle-step7.md](../../../../../../../../docs/verification/conversation-lifecycle-step7.md)
