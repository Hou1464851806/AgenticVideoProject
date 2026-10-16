# 对话生命周期步骤 3：Java 接纳、发布与草稿接受

日期：2026-10-16（Asia/Shanghai）。实施基准 `1a1cdf05bec8484b0e4d6b973e8ab3da36caccc4`，当前分支 `main`，起始工作区干净。依据[步骤 2 交接](conversation-lifecycle-step2.md)与[完整设计](../conversation-lifecycle-browser-design.md)，本次完成七步顺序中的第 3 步，不是整个阶段交付。

## 实施范围

- `AgentRuns` 接纳用户原文与明确引用，原子写用户消息、turn、AGENT_TURN、固定 RunInput／hash 和 Outbox。历史截止当前消息之前；问答不分配制作。沿用项目鉴权，不接纳旧制作表单覆盖。一次会话只允许一个排队／执行／取消中的 Agent；挂起等待不占此资格。
- Python 解析通过独立内部入口提交。Java 固定 scope、制作／thread／execution 和编辑代次，保存不可变执行绑定；重投不重新读取聊天或实时制作。新制作使用默认条件；延续制作提升代次；补答必须引用固定输入中的实际开放问题，沿原 scope／execution／代次恢复。来源由 Java 真实用户消息生成。条件投影沿用 Python 合并语义与 M2 完整条件 Schema，校验必须匹配该持久条件，不能由模型替换来源。
- `AgentRunTools` 在当前 task／attempt／lease／代次下接纳工具。网络读取在锁外；草稿校验及回执、代次元数据在同一事务提交。问答工具回执另以 task 与固定 scope 的复合外键归属；补答继承原任务回执，制作延续只继承同 production／thread／scope 的可信来源。浏览器制作不得通过旧 CLI 校验或 save 绕过事务；独立 CLI 行为保留。
- `AgentRunResults` 发布 ANSWER、WAITING_INPUT、DRAFT_READY、INCOMPLETE／FAILED／CANCELLED。结果核验固定身份、关系、scope 回执、实际已读 evidence、当前制作与校验。发布草稿只关联持久规范化内容，不创建版本。等待写问题并结束 attempt；补答移交关闭原等待并记录后继，原任务 SUCCEEDED 表示已交接。初始理解失败、scope 失效的失败终态可以持久化，不因同一故障拒收错误原因。
- `DraftAcceptances` 从用户所见 message／validation／两种 hash／代次接受当前草稿；未决消息或制作修改阻断，独立问答／讨论保留资格。在同一事务调用原 ProductionSaves 与 ExportRequests.confirm，写 USER_INTERFACE 确认、接受回执及方案聊天引用。重复同 key 同对象返回原结果，加锁后复核；确认失败整体回滚。保存后制作从 revision 来源推导封存，不新增平行状态机。
- 项目恢复快照增加 `agent`：当前制作、当前草稿及持久规范化内容、待回答问题、Java 计算的按钮资格。当前草稿独立读取，不受最近 100 条消息窗口限制。共享 TaskExecution 在新版运行的 task 锁之前取得项目→会话→制作锁，心跳、取消及公开活动沿用原任务底座；旧 complete 不能发布新版 Agent 结果。
- V55 只新增执行工具回执归属与草稿接受操作回执及外键，不修改历史迁移，不改 M2 Schema／hash，不清理 checkpoint、模型回执或业务数据。

默认 legacy 开关仍关闭。公共 `/turns` 的默认路径是本次新接纳；显式打开 legacy 的历史回归仍走原实现。LocalMedia 历史断言从“所有会话发送 503”更新为“新版发送接纳为 PENDING、旧 tasks HTTP 仍 410”，不宣称 PENDING 已由 Worker 消费。

## HTTP 与下一步交接

公共入口：

- `POST /api/v1/conversations/{conversationId}/turns`，原文 `content`、可选 `references`，`Idempotency-Key`；不传旧 `context` 表单。引用支持 ASSET、EVIDENCE、EDIT_DRAFT、PLAN_REVISION、QUESTION；均核对项目／会话归属。ASSET 引用可限定本轮快照；明确草稿问答沿该草稿 scope。
- `POST /api/v1/conversations/{conversationId}/drafts/accept`，body 为步骤 1 的 camelCase DraftBinding，`Idempotency-Key`。返回 `planId/revisionId/revisionNumber/confirmationId/outputs`，输出映射为 `outputKey/outputId/durationMs`。不接纳客户端草稿内容或条件来源。
- 既有会话／项目快照增加 `agent`，任务停止仍使用 `/api/v1/tasks/{taskId}/cancel`。

内部入口均使用原 Worker Bearer 认证，位于 `/internal/v1/agent-runs/{taskId}`；不要打开旧 `/internal/v1/tasks/**`：

| 操作 | 请求与返回 |
| --- | --- |
| `POST /claim` | 原 Claim：executorId、claimRequestId；返回稳定 input、inputHash、attemptId／leaseToken、claimed、已持久 binding（如有） |
| `POST /parsed` | attemptId、leaseToken、parsed（步骤 1 ParsedInput）；返回 identity、inputHash、parsed、scope、production、productionBinding、editGeneration、question |
| `POST /tools` | attemptId、leaseToken、editGeneration（独立问答为 null）、request（原 M1/M2 工具包络）；返回原工具反馈，外层使用 data 包络 |
| `POST /result` | attemptId、leaseToken、result（步骤 1 RunResult）；持久成功后重放同内容，不重复消息／草稿／问题 |
| `POST /heartbeat`、`/cancelled` | 原 Execution／Stopped 请求；核验新版运行后复用任务短事务 |
| `POST /events` | 原 PublicEvent 请求，只开放 activity.updated，复用原公开 phase，拒绝私有推理／文字 delta |

`binding.production` 是供接缝核对的 camelCase 制作摘要；调用 Python execute_run 的 production 参数使用 `binding.productionBinding`（原 M2 requirement_id/project_id/scope_id）。其余使用 binding.identity、scope、question、editGeneration。固定输入的身份保留接纳 thread；执行 identity 可以绑定原制作／等待 thread。parsed 与 result 不可互换；条件 patch 的键沿用 Understanding 的 snake_case。

步骤 4 尚需实现单实例 Worker、Agent 队列路由、MQ 消费／ACK、租约过期恢复、新 attempt、显式可恢复失败关联重试及原 Python checkpoint／付费回执恢复。新失败 retry 尚未开放，不能把 legacy retry 作为新版恢复。Worker 必须按 binding 复用原执行，不能把 Java 新 task 当成新付费预算；工具请求携带当前 lease／代次。公开问题和草稿 messageId 由可信 Worker 投影携带，Java 验证其归属后持久化。

步骤 5 的草稿预览来源／媒体消息、步骤 6 的前端卡片尚未实施；`canPreviewDraft` 表示草稿业务资格，不代表已有草稿预览 API。新版 Agent 接缝夹具使用 Spring MockMvc 控制器／认证过滤器与真实 PostgreSQL 18，不是新版 Agent Worker／MQ 链路或浏览器验收；既有 Java 媒体／分析回归另使用真实 RabbitMQ、存储和 FFmpeg，以及受控模型／索引服务。

## 分层验证

测试接缝沿用交接约定：Java HTTP／权限与持久事务边界、固定输入／结果契约、现有 CLI 校验保存边界。开发首先验证默认发送仍被旧 503 阻断的红测试，再接通新路径；之后按当前行为扩展模块验证。审查后补充的依赖失败测试先复现拒收失败结果，再修正；补答不重读、理解失败和 CLI 绕过有专项回归。开发中测试代码曾有 JsonNode 泛型编译失败及无效 CLI 请求先命中 400 的断言失败，均修正后重跑，没有把失败当作通过。

范围依据：本次修改 Java 对话、ProductionDrafts／Saves、共享 TaskExecution 的新版锁序和新数据库迁移，回归覆盖当前模块及直接调用的任务、读取／草稿工具、保存／版本、确认／预览／导出、会话恢复、真实媒体／分析消费者与认证。Python 核心未修改，仅补跑会话／M2／工具契约、Java 适配器及公共入口相关 5 文件。没有以阶段 7 的全量要求扩大本步为全仓重测。

| 检查 | 实际结果 |
| --- | --- |
| 新版 Agent API 专项 | 最终代码 13 项通过，0 失败／错误／跳过；包含公共输出字段、错误边界与显式 ASSET 范围用例 |
| Java 相关回归 | 20 个测试类，167 项通过，0 失败／错误／跳过；包括完整 V1～V55 迁移、V53 升级兼容、真实 Java 媒体／分析消费者及历史业务契约 |
| Java 类型／编译 | Maven compile 与 testCompile 通过；与测试同轮编译，不另宣称全后端打包 |
| Python 相关 5 文件 | 64 项通过，0 失败／跳过；未修改 Python，后续小修仅影响 Java API |
| 文档与差异 | 4 份文档 85 个本地链接可解析；git diff --check 通过 |

Java 20 类回归使用该轮编译时的版本；该轮编译后只调整了公共输出映射 camelCase 及专项用例，接纳契约错误映射范围已在该轮编译前纳入。最终补跑新版 API 专项，其他业务代码没有改变。不能把 167 项原运行称为这些小修后的全量重跑；上述按实际运行分别报告。最终 13 项专项通过后仅补录文档，未再修改代码。

最终所选回归无失败／跳过；开发红测试与修正已在上文记录。未运行：Java／Python 全量非付费回归、后端打包、前端类型／构建、新版浏览器验收、实际 Agent Worker／MQ 交接、真实付费模型、新草稿媒体预览与人工观看。完整阶段门槛仍安排在步骤 7；既有媒体自动回归不替代新版 Agent 或真实模型端到端验收。

复现命令（仓库根目录；内部模型服务均为受控夹具）：

```sh
mvn -q -pl backend '-Dtest=AgentRun*Test,ConversationLifecycleSchemaIntegrationTest,ConversationApiIntegrationTest,ConversationRecoveryApiIntegrationTest,ModelRecordLimitApiIntegrationTest,ReadToolsIntegrationTest,DraftValidationIntegrationTest,ProductionSchemaIntegrationTest,EditDraftContractsTest,TaskApiIntegrationTest,LocalMediaIntegrationTest,LocalAnalysisIntegrationTest,EditPlanApiIntegrationTest,PreviewApiIntegrationTest,ExportApiIntegrationTest,InternalPreviewApiIntegrationTest,AuthenticationApiIntegrationTest,AuthenticationRateLimitIntegrationTest,ToolJsonTest' test
mvn -q -pl backend -Dtest=AgentRunsApiIntegrationTest test
agent-service/.venv/bin/python -m pytest \
  agent-service/tests/test_conversation_contracts.py agent-service/tests/test_editing_contracts.py \
  agent-service/tests/test_tool_contracts.py agent-service/tests/test_java_executor.py \
  agent-service/tests/test_conversation_runtime.py -q
```

## Standards

独立初审无硬规范违反，指出按钮阻断 SQL 实际重复（P3）；已收敛为 AgentRuns.hasPendingMutation。收尾复核 0 项未解决发现。未抽象通用锁框架或另一套任务状态机。

## Spec

独立初审发现失败终态受失效依赖阻断（P1）、补答未继承原读取回执（P2）；均修正并增加公共边界回归。收尾复核 0 项未解决发现。同代次校验、冻结后的操作资格、CLI 绕过与补答 scope 归属复核未发现额外问题。

审查固定点为 `1a1cdf0`，使用 `git diff --cached 1a1cdf0`，本次提交前提交列表为空；审查覆盖实际新增文件，不将未跟踪文件遗漏。静态审查与运行验证分别记录。
