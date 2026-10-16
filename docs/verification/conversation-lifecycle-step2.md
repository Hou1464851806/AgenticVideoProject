# 对话生命周期步骤 2：Python 公共入口与持续会话

日期：2026-10-16（Asia/Shanghai）。实施基准 `80f372ec2a385ddf14f9939b6a0551d4eaa72ea8`，分支 `main`，起始工作区干净。依据[步骤 1 交接](conversation-lifecycle-step1.md)与[完整设计](../conversation-lifecycle-browser-design.md)，本次仅实施七步顺序中的第 2 步，不是完整阶段交付。

## 落地边界

公共入口分成 `Agent.prepare_run(RunInput)` 和 `Agent.execute_run(RunInput, identity=..., scope=..., production=..., edit_generation=..., question=...)`。前者复用 Understanding／图节点做固定输入理解，后者接收 Java 授权的执行绑定；编排集中于 `runtime/conversation.py`，图中没有 MQ、任务租约或业务发布代码。输入及关联对象沿用步骤 1 的严格契约，M2 工具 Schema、草稿／条件 hash 与校验规则不变。

QUERY／DISCUSS 的本轮 intent 与持久制作条件分离；问答中的素材选择仍约束工具和证据。真实 DISCUSS 可讨论一般剪辑思路，QUERY 保持已读证据、版本、时间范围、视觉种类与覆盖核验。模型不能以讨论为由生成草稿，自然语言媒体操作按提示说明对应页面操作。

制作 thread 保存范围、证据、条件、草稿和等待。独立消息在本轮 thread 执行，不会写原制作；固定历史由 Java 传入，当前原文只单独传一次，公开制作摘要不作为原始视频证据。修改使用稳定制作 thread 与可信的新 execution／编辑代次；新制作从默认条件开始。只有 DRAFT 合并条件，来源使用真实 messageId。原有效校验不因问答清空；本地 `draft_ready` 记录跨问答保留的保存资格，修改开始便撤销，只有整体校验成功恢复，防止修改失败后问答重新开放旧稿。浏览器的最终操作资格仍由步骤 3 的 Java 事务判断。

真实补答必须明确 ANSWER_QUESTION／questionId，并与固定输入、原 wait／thread／execution／制作／scope／checkpoint 代次匹配。恢复将入口已解析 Understanding 注入 apply_input，不再次整体理解；工具、修正、决策计数及模型调用序号延续原执行。入口理解的实际调用数与活动耗时通过 SQLite 原子记录归集一次，保留原付费回执身份；额度不足停止图动作，不因新 task 或重投重置。

现有 SQLite 新增本地 `conversation_runs` 与 `execution_transfers`：固定输入、首次执行绑定、起始值、解析与核心结果持久复用，不新增 Java 任务状态机。相同 task 改输入／绑定快速失败；结果重放不读取新聊天，也不回写已经推进的制作 thread。RECEIVED 回执继续复用，SUBMITTING／UNKNOWN 仍停止自动重付。没有重建或清理既有 checkpoint／回执。

CLI send／resume／save 保留。已保存制作可继续问答与讨论，修改仍只读，明确新制作使用独立身份。浏览器制作拒绝 CLI save；保存与确认由步骤 3 实现。核心新增 PARSED 只用于本地交接，核心视图不是公开 RunResult；公开问题／草稿消息 ID、scope 回执与结果投影由后续 Java／Worker 接缝完成。

## 分层验证范围与过程

预先确认的测试接缝沿用设计退出标准：核心公共入口、SQLite 跨实例恢复，以及模型／Java HTTP 边界。测试使用 ScriptedModel、既有 httpx.MockTransport 与实际 LangGraph／SQLite；不是实际 Java 服务、真实模型或浏览器验收。

开发按单项行为先失败再实现，随后运行对应文件及直接相关回归。失败先后揭示公共入口缺失、终态重放缺少持久用量、问答后 CLI 无法保存原合法草稿；均已由相应实现修正。拆分意图后调整两项旧断言：讨论主题不再写制作条件，真实 DISCUSS 不再列入未开放模式。另新增失败修改后插入问答仍不可保存、冻结后新制作不继承条件、旧任务重投不覆盖后续编辑、未知提交不重付、RECEIVED 后崩溃恢复及预算归集后崩溃不重复计数等场景。

选择范围依据：本次修改 Python 图的理解、决策、结果、等待与付费回执存储，并调整公共保存资格，因此覆盖 Agent 场景／恢复／限额／历史缺陷、真实模式草稿和 Java 工具适配、模型协议、M2／会话契约及 CLI 预览兼容；不修改 Java、数据库迁移、MQ 或前端。阶段 7 才执行全量非付费回归、后端打包与有效浏览器验收。

最终收尾增加 ANSWER／WAITING 两种“checkpoint 已提交但本地结果尚未记录”的崩溃重投测试：WAITING 先暴露恢复视图缺少 SQLite 用量，随后将无补答的等待恢复统一交给 inspect 读取，保证不推进图、不消费问题、不新增模型调用。修正后重跑全部所选相关回归；不是沿用此前 183 项结果。

| 检查 | 最终实际结果 |
| --- | --- |
| 新公共入口专项 | 15 项场景通过（含参数化）；无失败／跳过 |
| Python 13 个相关测试文件 | 最终代码 185 项通过，0 失败／跳过；含 CLI 预览／保存／恢复兼容 |
| Pyright 本次新增及理解／等待相关 8 个文件 | 0 errors、0 warnings |
| 整个 runtime 的 Pyright | **未通过**：实施前基准 102 项诊断，最终 30 项既有诊断；按文件名、规则和诊断文本对比无新增。主要为旧决策／工具／runner 的可空值、TypedDict 和动态 RunnableConfig 类型；没有扩大为全仓类型整治 |
| Python compileall | agent_service 与 tests 全部通过；不等同于类型检查通过 |
| 文档与差异 | 4 份文档的 75 个本地链接可解析；git diff --check 通过 |

类型检查中将节点始终取得的状态字段标为 Required，并在等待节点用 cast 表达图保证的非空输入，不新增运行时空值回退。新增文件及理解／等待路径的类型检查单独通过，不能据此声称整个 runtime 类型检查通过。初次检查曾出现新增 Optional 属性诊断，已将代次核验移入明确存在 current 的分支后修正。

本轮最终所选运行测试没有失败或跳过；开发过程的红测试及类型检查未通过项已如实记录。未运行：Java／Python 全量非付费回归、Java 编译／后端打包、数据库迁移、前端类型检查／构建、浏览器、真实付费模型和 FFmpeg 媒体验收。本步没有修改双方 wire Schema／hash 或 Java 业务代码，不将旧步骤的 Java 验证算作本步重跑；阶段全量仍留在步骤 7。

复现命令（仓库根目录）：

```sh
agent-service/.venv/bin/python -m pytest \
  agent-service/tests/test_conversation_runtime.py \
  agent-service/tests/test_agent_scenarios.py agent-service/tests/test_resume_and_limits.py \
  agent-service/tests/test_real_production.py agent-service/tests/test_review_regressions.py \
  agent-service/tests/test_model_protocol.py agent-service/tests/test_java_executor.py \
  agent-service/tests/test_conversation_contracts.py agent-service/tests/test_editing_contracts.py \
  agent-service/tests/test_tool_contracts.py agent-service/tests/test_java_read_tool_contract.py \
  agent-service/tests/test_preview_cli.py agent-service/tests/test_preview_client.py -q
uvx --from pyright pyright --pythonpath agent-service/.venv/bin/python \
  agent-service/agent_service/runtime/conversation.py agent-service/agent_service/runtime/state.py \
  agent-service/agent_service/runtime/persistence.py agent-service/agent_service/runtime/calls.py \
  agent-service/agent_service/runtime/nodes/request.py agent-service/agent_service/runtime/nodes/waiting.py \
  agent-service/agent_service/prompts.py agent-service/tests/test_conversation_runtime.py
agent-service/.venv/bin/python -m compileall -q agent-service/agent_service agent-service/tests
# 下列为已知未通过的整个 runtime 类型检查：
uvx --from pyright pyright --pythonpath agent-service/.venv/bin/python agent-service/agent_service/runtime
```

## Standards

独立 Standards 子代理初审与收尾复核均为 0 项硬违反、0 项需处理的判断性异味。编排集中于 conversation.py，图中没有 MQ／租约／业务发布职责；边界校验、错误传播、最小改动与分层测试符合 AGENTS.md、GLOSSARY、双方 README 及 ADR-0007／0018／0019。等待恢复复用 inspect，职责一致。

## Spec

独立 Spec 子代理初审与收尾复核均为 0 项发现。固定输入与解析、意图隔离、独立问答、明确补答、预算及调用序号连续、真实消息来源、冻结后会话／新制作、回执复用与未知提交保护均符合步骤 2。未把 Java 事务、Worker／MQ、公开结果投影、媒体或前端算作本步完成。最后两种结果交接崩溃测试及修正复核无新增问题。

两轴均以实施前 HEAD 为固定点，对本次暂存差异进行独立只读审查；尚无本次新提交，命令为 git diff --cached 80f372ec2a385ddf14f9939b6a0551d4eaa72ea8，提交列表为空。收尾复核同时读取最终工作区。审查结论：Standards 0 项，Spec 0 项。静态审查与上表运行验证分别报告。最终 185 项回归及 8 文件类型检查、compileall 均在最后代码修正后执行，之后仅补录文档。

## 下一步交接

步骤 3 实现 Java 新接纳／执行绑定／结果发布／确认事务，必须核对权限、scope、当前制作、代次、条件来源、task／attempt／lease 及等待移交，并遵循统一锁序。Python 的本地一致性检查不能代替这些事务。

步骤 4 Worker 使用固定 RunInput 调 prepare_run，持久发送解析结果给 Java；取得 Java 确定的绑定与完整 scope 后调 execute_run。每个执行使用独立 JavaToolExecutor，原 questionId 仅从固定输入选择。不要用新 send 代替恢复，不要把核心 PARSED／WAITING 直接作为 wire 状态发布；须按步骤 1 RunResult 投影。浏览器校验传输还须接入步骤 3 的 task／attempt／lease／编辑代次事务，当前测试复用 M2 HTTP 夹具不表示旧 CLI 校验端点已适用于浏览器。

本步不开放 legacy 开关、不新增 Worker 或 HTTP 路由、不调用付费模型、不运行真实媒体。后续步骤仍需实际 Java／PostgreSQL／MQ、浏览器和真实模型／媒体验收。
