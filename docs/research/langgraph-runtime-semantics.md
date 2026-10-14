# LangGraph 运行语义与本项目兼容性核对

核对日期：2026-10-14。本文记录官方 API 事实及其适用边界，供 Agent 运行设计引用；不代表功能已经实现或运行验证通过。

## 1. 当前依赖基线

本仓库 `agent-service/requirements.txt` 与 `requirements.lock` 均锁定 `langgraph==1.2.14`；lock 同时包含 `langgraph-checkpoint==4.2.0`、`langgraph-checkpoint-sqlite==3.1.1`、`aiosqlite==0.22.1`。当前 `conversation.py` 使用同步 `StateGraph` 节点、`SqliteSaver`、`invoke`，以 `conversationId:turnId` 为 checkpoint thread 身份，并显式设置 `recursion_limit=32`。这些是仓库事实，未在本次研究中重装依赖或运行服务。

| API / 能力 | 与当前锁定版本的关系 | 使用条件 |
| --- | --- | --- |
| `StateGraph`、`context_schema`、`Runtime`、reducer | 可直接设计使用 | 将可序列化执行状态与调用时依赖区分开 |
| `interrupt`、`Command(resume=...)` | 可直接设计使用 | 持久 checkpointer、稳定 `thread_id`、恢复入口 |
| `RetryPolicy` | 可直接设计使用 | 针对异常显式筛选，重试仍须预算与幂等约束 |
| `timeout` / `TimeoutPolicy`、`NodeTimeoutError` | 可直接设计使用，官方最低要求为 1.2 | 仅限 async 节点；当前同步节点需改造 |
| `error_handler` / `NodeError`、`set_node_defaults` | 可直接设计使用，官方最低要求为 1.2 | 仅处理已知可恢复错误，handler 自身失败须上报 |
| `durability="sync"` | 可直接设计使用 | 在 graph 执行方法上传入，且必须配置 checkpointer |
| `AsyncSqliteSaver` | 现有 SQLite checkpoint 包已有相应能力 | 随 `ainvoke` / `astream` 改用 async saver |
| `AsyncPostgresSaver` | 当前 lock 没有对应包 | 若选择 PostgreSQL checkpoint，新增 saver 与驱动依赖，再锁定和验证 |

因此上述运行方案不以升级 LangGraph 核心版本为前置条件。证据包含官方 [fault tolerance 的版本要求](https://docs.langchain.com/oss/python/langgraph/fault-tolerance)、固定 [1.2.14 StateGraph 源码](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/graph/state.py)、[checkpoint 实现与 async 要求](https://docs.langchain.com/oss/python/langgraph/checkpointers#checkpointer-libraries)。

## 2. 节点边界、状态与上下文

官方指南以职责、外部服务、错误模式和观察中间结果的需要来拆分节点。状态保存后续节点需要的原始数据；提示词在使用时组装。小节点降低失败后重复执行的范围，但不要求把每个纯辅助函数做成节点。[Thinking in LangGraph](https://docs.langchain.com/oss/python/langgraph/thinking-in-langgraph#advanced-considerations)

`StateGraph` 节点返回状态的部分更新。`context_schema` 定义运行期上下文；`runtime.context` 可以提供模型客户端、业务客户端和配置。它是每次 graph 调用注入的上下文，不是靠 checkpoint 自动重建的资源。固定版本的执行器用本次调用的 `context` 构造 `Runtime`。恢复时应重新传入客户端、执行权和取消检查依赖；需跨暂停保留的身份与配置版本放入 state 或业务记录。[StateGraph 定义](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/graph/state.py)、[Runtime 定义](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/runtime.py)、[执行器的 Runtime 构造](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/pregel/main.py)

每个状态字段独立应用 reducer；未声明 reducer 时更新覆盖原值。默认 `LastValue` channel 在同一 superstep 收到多个更新会抛出 `InvalidUpdateError`。采用并行检索时不能让多个节点覆盖同一 `evidence` 字段；应约定可合并、按稳定身份去重的 reducer，或拆分写入字段。append reducer 收到空列表不会清空旧列表；需按 reducer 合同处理重置，例如 `Overwrite`。[Reducer 语义](https://docs.langchain.com/oss/python/langgraph/graph-api#reducers)、[固定版本 LastValue 实现](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/channels/last_value.py)

## 3. `interrupt` 暂停与恢复

`interrupt(payload)` 通过专用异常暂停 graph，并将可序列化 payload 交给调用方。恢复必须以同一 checkpoint thread 调用 `Command(resume=value)`；`value` 会成为原 `interrupt` 的返回值。执行从暂停节点的开头重来，暂停之前的节点内部代码也会再次运行。多个 interrupt 的返回值按节点内调用顺序匹配，而非按业务问题文案匹配。[1.2.14 interrupt / Command 定义](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/types.py)

以下是官方明确的限制：

- 不用裸 `except` / `except Exception` 吞掉 interrupt；将可失败操作与 pause 分开。
- 不改变同一节点内 interrupt 的调用顺序或按不稳定条件跳过。
- payload 使用可序列化的简单值。
- pause 之前的副作用必须幂等，或移入独立节点。

这些规则支持“先登记等待请求，再进入纯暂停节点”的拆分。等待请求登记与 checkpoint 属于两个持久化系统，仍需应用自己的握手、恢复和事件去重；LangGraph 文档并未提供对 Spring 业务事务的原子提交保证。[Interrupts 的规则](https://docs.langchain.com/oss/python/langgraph/interrupts#rules-of-interrupts)

失败恢复使用 `invoke(None, config)` 与提供 interrupt 返回值的 `Command(resume=...)` 是不同入口。已完成 graph 的后续新请求使用普通输入字典重新进入 `START`；仅传 `Command(update=...)` 不会建立新一轮执行。[Command 输入规则](https://docs.langchain.com/oss/python/langgraph/graph-api#input-to-invoke-or-stream)

适用项目的推论：用户问题、异步分析作业均应在业务层带身份与当前有效状态校验；普通新消息和未知回调不能自动作为 resume。`interrupt` 是暂停原语，本身不创建作业、不调度超时唤醒，也不保留 HTTP 请求或 worker 执行权。

## 4. checkpoint、重放与副作用

完整 checkpoint 位于 superstep 边界。同一 superstep 可以执行多个节点，不能把并行 graph 简化为“每完成一个节点就产生一个完整 checkpoint”；成功节点的 pending writes 可用于同一步其它节点失败后的恢复。`StateSnapshot.tasks` 还提供错误与 interrupts，不能仅凭 `values` 非空判断运行已完成。[Checkpointers 核心概念](https://docs.langchain.com/oss/python/langgraph/checkpointers#core-concepts)

Durability 控制 graph 状态写入的时机：

| 模式 | 官方语义 | 本项目适用边界 |
| --- | --- | --- |
| `sync` | 下一步开始前完成 checkpoint 写入 | 适合等待、外部提交前后的执行恢复 |
| `async` | 下一步运行同时写 checkpoint；进程崩溃可能丢失最近 checkpoint | 不能把进度事件等同于已落盘确认 |
| `exit` | 退出、出错或 interrupt 时持久化 | 不提供进程中途崩溃后的中间状态恢复 |

没有 checkpointer 时 durability 不生效。[Durability 模式](https://docs.langchain.com/oss/python/langgraph/checkpointers#durability-modes)、[1.2.14 执行器](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/pregel/main.py)

`sync` 不是外部副作用 exactly-once 保证。远程请求已成功、但结果尚未保存的窗口仍存在。官方 task 指南同样要求未完成 task 再执行时保持幂等。`@task` 可以恢复已保存的操作结果，但不能替代 provider receipt、业务幂等键或结果核对；当前项目已有 provider `PREPARE/SUCCEEDED/UNCERTAIN` 与业务 `producerKey`，这些机制仍须保留。[Functional API 的 determinism / idempotency](https://docs.langchain.com/oss/python/langgraph/functional-api#idempotency)

从历史 `checkpoint_id` 重放还会再次执行该点之后的 LLM/API/interrupt。应用不能把调试重放当成对生产操作安全的只读查看。[Replay 规则](https://docs.langchain.com/oss/python/langgraph/checkpointers#replay)

## 5. 重试、超时与错误处理

节点异常按“attempt timeout → retry 策略 → 重试耗尽后的 error handler”处理。`max_attempts=3` 包含第一次执行；每次 retry 的 timeout 重新计时。`NodeTimeoutError` 默认可重试。interrupt 使用特殊控制流，绕过 retry 与 error handler。[Fault tolerance](https://docs.langchain.com/oss/python/langgraph/fault-tolerance)

默认重试并不等于项目期望的错误分类。固定版本 `default_retry_on` 对 `httpx.HTTPStatusError` 只接受 5xx，因此百炼 429 需要显式策略；鉴权、参数、范围错误不应靠重复网络调用修复。从源码推得一个适配器差异：1.2.14 的 `requests.HTTPError` 分支使用 `if exc.response`，与 `requests.Response` 对错误状态返回 false 的行为组合后，可能将 4xx 也视为可重试。推荐对实际适配器异常定义显式白名单，不依赖默认行为。[1.2.14 default_retry_on 源码](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/_internal/_retry.py)、[Requests Response 的布尔语义](https://requests.readthedocs.io/en/latest/_modules/requests/models/)

LangGraph retry 会清除失败 attempt 的 graph writes 再运行节点；在异常前“写入 state 的调用计数”不能因此作为可靠的扣费记录。外部服务已经发生的调用不会被回滚。这是把预算预留和模型/工具回执保留在业务账本的理由。[1.2.14 retry 执行实现](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/pregel/_retry.py)

节点 timeout 只支持 async 函数，同步节点加 timeout 会被拒绝。即便用 `asyncio.to_thread` 包裹阻塞调用，取消 await 也不能保证结束底层线程或撤回远程请求；仍需 HTTP 客户端超时和结果核对。不要给包含同步暂停/纯处理节点的 graph 统一设置 timeout，再假定所有节点都能编译。[Timeout 支持范围](https://docs.langchain.com/oss/python/langgraph/fault-tolerance#timeouts)、[Python asyncio.to_thread](https://docs.python.org/3/library/asyncio-task.html#asyncio.to_thread)、[正在执行的线程任务无法通过 Future.cancel 取消](https://docs.python.org/3/library/concurrent.futures.html#concurrent.futures.Future.cancel)

`error_handler` 接收带异常的 `NodeError`，可以用 `Command` 转入降级或补偿节点。handler 不能通过配置默认 handler 递归处理自身失败。未知错误仍应上报；仅在预先定义的错误分类上降级成功。[NodeError 定义](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/errors.py)、[默认策略对 handler 的适用边界](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/graph/state.py)

还有一个 wiring 限制：1.2.14 把 `error_handler` 注册为独立的内部节点，而失败节点的静态／条件出口仍附着在失败节点上。handler task 使用自身节点的 writers，不继承原节点出口。因此只返回普通 state update 可以更新字段，却不能假定会继续原节点的下一步；若恢复后要进入 `verify_progress`，应由 handler 显式返回 `Command(update=..., goto="verify_progress")`。这不会与失败节点的正常出口叠加，因为失败 attempt 没有成功发布该出口。另一种方式是在业务 wrapper 内将可处理故障转换为普通返回值，使原节点正常完成并走自己的边。[内部 handler 注册与边的附着](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/graph/state.py)、[prepare_node_error_handler_task 实现](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/pregel/_algo.py)

## 6. `recursion_limit` 与业务预算

`recursion_limit` 是一次 graph 执行允许的 superstep 数量，放在 config 顶层。它不是 LLM 轮次、工具数量、费用或累计运行时长；并行节点共用一步，节点内部 retry 也不等价于新一步。[Recursion limit](https://docs.langchain.com/oss/python/langgraph/graph-api#recursion-limit)

固定版本 loop 从最新 checkpoint 的 step 建立新的 `stop = step + recursion_limit + 1`；后续恢复调用会获得新的本次执行步数容量。因此跨暂停、重试和重新领取任务的预算必须独立持久化。`RemainingSteps` 适合提前留出收尾节点空间，但只是当前 invocation 的步数余量。[1.2.14 loop 初始化](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/pregel/_loop.py)、[RemainingSteps 实现](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/managed/is_last_step.py)

适用项目的推论：LLM、工具、候选修复等预算的检查应在每次实际操作之前；等待耗时与有效执行耗时分别记录。恢复不能重新初始化额度，重试不能脱离余额重新发起付费操作；框架的步数上限只承担失控循环的最后防线。

## 7. 边与 `Command` 的一个必要约束

`Command(goto=...)` 增加动态目标，并不会覆盖该来源节点已有的静态 `add_edge`；同时配置两者会使两个目标都运行。`Command[Literal[...]]` 或 `destinations` 的目标声明辅助图渲染，不能当作运行期工具权限校验。每个来源节点应明确选择路由机制；生产动作仍在业务模块校验范围、方案版本与授权。[Command 路由规则](https://docs.langchain.com/oss/python/langgraph/graph-api#command)、[destinations 的说明](https://github.com/langchain-ai/langgraph/blob/1.2.14/libs/langgraph/langgraph/graph/state.py)

## 8. 验证范围

本次完成：锁文件与现有 graph 的静态阅读、LangChain 官方文档核对、`1.2.14` 固定 tag 源码核对。未完成或未声称完成：本机实际安装版本确认、async saver smoke test、外部模型重试契约测试、进程崩溃注入及业务等待握手测试。设计实施时，这些验证应围绕恢复与副作用一致性展开。
