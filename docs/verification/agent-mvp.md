# Python Agent MVP 验收记录

2026-10-14，本机 macOS / Python 3.12.3 / Docker 29.8.2；容器 Python 3.13 独立 CLI 也已构建并运行。实现依据 [已确认设计](../agent-mvp-design.md)，运行方法见 [Agent README](../../agent-service/README.md)。本阶段只验证独立 Agent，不代表 Java 工具或真实视频分析已完成。

## 验收结论

MVP-01～MVP-16 已按下面证据验证。默认测试 **54 passed, 1 skipped**，跳过项为隔离保留的真实百炼 ASR 测试。真实 DeepSeek Flash + 固定工具 Mock 已完成问答、草稿和关联澄清。Langfuse 4.59.0 在本机 UI 中可见真实 graph / 节点 / generation / token 用量，工具记录标为 Mock；Observations API v2 也已核对。

| ID | 验证方式与结果 |
| --- | --- |
| MVP-01 | 默认测试直接调用核心；独立 CLI 和新构建 Docker CLI 返回 ANSWER。验收期间只启动独立 Langfuse 栈，无 Java/RabbitMQ/业务 DB/Qdrant |
| MVP-02 | 确定性测试验证已读引用与无命中；真实答复引用 speech-cache，lecture 的 15000–45000ms，核对文字与固定原始样本一致 |
| MVP-03 | 确定性测试验证目录页必须连续、语音不能充当画面；真实问答/草稿包含两页 lecture overview 和原始读取。目录条目持久保留供后续决策 |
| MVP-04 | 测试验证多输出独立时长；真实草稿仅含 lecture 15000–45000ms，引用 speech-cache，模拟校验 valid=true、durations_ms=[30000]，DRAFT_READY |
| MVP-05 | 注入非法时长后有限修正；陈旧 draft_hash 导致 FAILED，不会批准新草稿。条件修改后的新草稿重新校验 |
| MVP-06 | 脚本与真实时长冲突都 USER_INPUT 暂停。真实 30秒/≤20秒冲突经关联补答选择30秒，最终保持原素材范围并完成 |
| MVP-07 | PENDING 退出，后续独立 CLI complete-mock 注入回执；原工具动作计数保持1。错误输入类型、waitId、callId 均被拒绝，不轮询 |
| MVP-08 | 两个 subprocess 测试分别验证用户等待和工具等待的进程退出/恢复；身份和预算保持。收到响应后、checkpoint 前注入异常，恢复复用原响应 |
| MVP-09 | 后续修改保留未提及条件并使旧校验失效；自然语言 NEW 迁移到新 thread，目标、条件、观察、草稿不继承 |
| MVP-10 | 单一 ToolExecutor 接口与严格参数 Schema；禁用导出不会执行；多动作/错误结构/截断响应拒绝；状态 ERROR/PENDING 必须带相应结构 |
| MVP-11 | 小模型/工具/修正/耗时/反馈大小额度触发 INCOMPLETE；重复读取有限停止；无 LLM 额度仍模板收尾。工具和模型 I/O 期间也检查取消/有效耗时 |
| MVP-12 | 临时工具失败最多2次尝试；未知异常上抛并记录 SQLite FAILED。模型回执丢失后不重付；收到响应后故障恢复保持总调用2次 |
| MVP-13 | 等待、模型 I/O、工具 I/O 三种取消验证，迟到输入不恢复；不保证撤销远端费用 |
| MVP-14 | 本机 UI 截图及 v2 API 清单见下；真实模型为 deepseek-flash。澄清三片段共享 execution_id；工具等待恢复不重新提交；回执复用记录1个 reused span、0个 generation |
| MVP-15 | 实际 stop Langfuse Web 后运行真实模型：stderr 明确 ConnectError 观测警告，ANSWER、4次模型回执仍写 SQLite；本次无 trace，不计观测闭环。之后恢复服务 |
| MVP-16 | README 覆盖安装/配置/启动/补答/工具恢复/取消/测试；runtime/dev 依赖均锁定，镜像固定 digest；默认 pytest 不调用付费模型 |

## 审查后的修复与复审

首次审查发现6项问题：取消路径绕过 Store、执行器隐式依赖 Mock.assets、连续澄清丢失硬条件、旧范围证据仍被批准、多素材全片检查只要求任意完成、恢复回执绕过32KiB限制。新增回归在修复前运行结果为9 failed / 3 passed，确认可复现而非仅格式意见。

修复将取消写入收敛为 Store.cancel，注入执行器要求显式 assets，保留有效条件补丁与有序关联补答，核对当前范围/分析版本/时间和每份目标素材覆盖；普通调用与恢复入口共用 ToolResult.bounded，超限回执在写入 Command/checkpoint 前替换为明确错误，不静默截断。矛盾条件继续等待，不写入非法条件；关系 AMBIGUOUS 时不合并到旧需求。

复审又发现草稿分支也应核对当前来源，现答案和草稿共享 source_error；草稿额外核对片段范围与当前素材时长。回归覆盖版本变更、时长缩短与素材退出范围，不批准旧草稿。最终共16个新增回归，完整54 passed / 1 skipped；两次修复后的最终独立 Standards 与 Spec 审查均为0 findings。源码仍维持10个节点和原模块职责，没有新增通用抽象。

当前版本重新验证真实 DeepSeek 问答、严格30秒草稿、时长冲突补答；独立 CLI 工具等待跨进程恢复、Docker CLI、本机 Langfuse observation 检查均通过。以下原始运行证据保留，复审后的运行以 results/traces JSON 中 review 前缀项为准。

## 实际调用证据

[完整样本运行结果](agent-mvp-results.json)只含固定样本、运行身份和预算，凭据不入库文档。[实际 trace 清单](agent-mvp-traces.json)包含 observation 数量、真实 token 用量与本机详情 URL；由 `scripts/verify_langfuse.py` 读取当前服务核对，检查 DeepSeek/Langfuse secret 不出现在 observation 内容中。

| 场景 | 模型请求 / 工具动作 | 结果与 trace |
| --- | --- | --- |
| 原始语音问答 | 6 / 4 | ANSWER，trace `f3b5e8695c3dab5d83582583bcb4c22f`，6个真实 generation，14556 total token（含缓存输入） |
| 严格30秒草稿 | 5 / 4 | DRAFT_READY，trace `048778c17e992428e8214953ee0e8ef2`，5个真实 generation，12146 total token |
| 关联澄清 | 原执行累计12 / 7 | WAITING → WAITING → DRAFT_READY；trace `42d0f422c69201c55842bf95e7d9ed13`、`f10444c561e739c9a7edd4c702d11fef`、`5b80663a234b5896fcb1c13c4052f663` |
| Mock工具等待 | 脚本模型，无真实费用 / 1 | WAITING → ANSWER，trace `6b04a5ac32b2f5be071e2ea73f4d0a28`、`534c4f1872816609f35f0a8e0f64c341`，恢复无新 TOOL |
| 已收回执复用 | 脚本模型，无真实费用 / 0 | 注入响应入库后崩溃，恢复 trace `de7a3c19d26bc355e8e0b575880cfb16`，1个复用 span，0个 generation |
| 观测停机 | 4 / 1 | ANSWER，无 trace，stderr 观测警告；SQLite 存在4个 RECEIVED 回执 |

本机 [Langfuse 项目](http://localhost:3035/project/agent-mvp-project/traces)已初始化。登录信息在未纳入 Git 的 `agent-service/.env.langfuse`。截图展示真实 ChatDeepSeek generation、模型名 deepseek-flash、token 和 LangGraph 节点：

![本机 Langfuse 验收](assets/agent-mvp-langfuse.png)

## 可复现命令与边界

```sh
cd agent-service
uv pip sync requirements-dev.lock
.venv/bin/python -m pytest -q
.venv/bin/python -m compileall -q agent_service
uv pip check
.venv/bin/python scripts/verify_langfuse.py .local/observed-query2.json .local/observed-draft2.json
```

最后一个命令读取本次本机运行文件；新的环境按 README 重新运行真实 CLI，将新生成结果 JSON 传入核对脚本即可。`git diff --check` 无错误。容器检查：根目录 `docker build -t agenticvideo-agent-mvp -f agent-service/Dockerfile .`，再挂载 `tests/fixtures` 并用 `--script /fixtures/query.json` 运行，返回带 speech-cache 引用的 ANSWER。

真实模型仍可能提出多余澄清、重复读取或错误格式：本次关联澄清在解决时长冲突后多问了一次读取许可，随后提示明确读取已授权，最终在12次原额度内完成，未重置预算。这是有限停止/恢复机制的验收，不是所有提示词都能一次成功的保证。首版强制重付 CLI 未开放；SUBMITTING/UNKNOWN 需人工核查，不能声称 checkpoint 能保证远端恰好执行一次。

依赖版本固定：LangGraph 1.2.14、SQLite saver 3.1.1、langchain-deepseek 1.1.1、LangChain 1.4.5、Langfuse Python 4.18.0；服务镜像4.59.0。SDK 4 采用 propagate_attributes、Observations API v2，参考 [官方 SDK 文档](https://langfuse.com/docs/observability/sdk/overview)。root Compose 的旧 Agent 服务被 profile 隔离，HTTP/MQ、Java 权限/执行、真实 ASR/VLM/Embedding、保存/预览/导出仍属于后续阶段。
