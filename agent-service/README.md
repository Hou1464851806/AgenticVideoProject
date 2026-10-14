# Independent Python Agent MVP

本目录只负责 LangGraph 决策、条件、证据、草稿、等待、预算和恢复。工具通过 `ToolExecutor` 执行；当前仅有固定样本 Mock，Java 执行器留待后续接入。ASR/VLM/Embedding 旧适配器隔离在 `analysis/`，CLI 不会调用它们。

## 安装与运行

在 `agent-service` 目录执行（Python 3.12，已验证 3.12.3）：

```sh
uv venv --python 3.12
uv pip sync requirements-dev.lock
cp .env.example .env
```

在 `.env` 填入 `DEEPSEEK_API_KEY`。也可使用已有根目录 `.env`；现有环境变量优先。真实运行显式使用 `deepseek-flash`，关闭 thinking、非流式，SDK 不自动重试，结构化 function calling 的本地校验不依赖 Beta strict。

```sh
.venv/bin/python -m agent_service run 'Mock素材lecture中的缓存读取流程是什么？'
.venv/bin/python -m agent_service run '只用lecture素材生成严格30秒的缓存流程剪辑草稿'
```

进度输出到 stderr，结果 JSON 输出到 stdout。结果始终带 `mock: true`。`DRAFT_READY` 仅代表模拟校验通过，没有保存或渲染。`WAITING` 正常退出；`INCOMPLETE` / `FAILED` 退出码 1。`--no-trace` 显式禁用观测。

## 本机 Langfuse

```sh
.venv/bin/python scripts/setup_langfuse.py
docker compose --env-file .env.langfuse -f compose.langfuse.yaml up -d
curl -f http://localhost:3035/api/public/health
```

打开 <http://localhost:3035>。账号和密码见私有 `.env.langfuse` 的 `LANGFUSE_INIT_USER_EMAIL` / `LANGFUSE_INIT_USER_PASSWORD`；初始化脚本不打印凭据，已有文件不会覆盖。CLI 自动加载这个文件。独立数据卷与项目业务设施隔离，Web 仅绑定本机 3035，样本对象存储仅绑定本机 9095。镜像已按本次验证的 digest 固定，Langfuse 服务版本 4.59.0。

每次运行或恢复生成一个 trace，使用相同 execution_id 关联恢复片段；LangGraph callback 记录节点，真实 DeepSeek 调用记录 generation 和 token，执行器边界记录带 Mock 标识的工具 observation。脚本模型不会伪造 generation。Langfuse 不可用会有终端警告，Agent 仍使用 SQLite 正常运行。

```sh
docker compose --env-file .env.langfuse -f compose.langfuse.yaml stop
```

停服务保留观测数据；下次 `up -d` 恢复。观测不能作为任务状态或付费回执数据库。

## 离线场景与跨进程恢复

所有全局选项位于子命令前。离线模式显式用 `--script`，不调用付费模型：

```sh
.venv/bin/python -m agent_service --no-trace --script tests/fixtures/query.json run '缓存如何读取？'
.venv/bin/python -m agent_service --script tests/fixtures/clarify.json run '缓存草稿'
.venv/bin/python -m agent_service --script tests/fixtures/clarify.json reply THREAD_ID '严格30秒'
.venv/bin/python -m agent_service --scenario pending --script tests/fixtures/pending.json run '查看画面'
.venv/bin/python -m agent_service --scenario pending --script tests/fixtures/pending.json complete-mock THREAD_ID
.venv/bin/python -m agent_service status THREAD_ID
.venv/bin/python -m agent_service resume THREAD_ID
.venv/bin/python -m agent_service cancel THREAD_ID
.venv/bin/python -m agent_service run '缩短到20秒' --thread THREAD_ID
.venv/bin/python -m agent_service run '另一个需求' --thread THREAD_ID --new
```

替换运行结果中的 THREAD_ID。恢复时使用相同 `--data-dir`、脚本与场景；默认 SQLite 位于 `.local/agent.sqlite`。`complete-mock` 是开发时显式发送最终结果，不是轮询。也可通过 `tool-result THREAD_ID result.json --wait-id WAIT_ID` 注入符合 `ToolResult` Schema 的回执；普通补答用 `reply`。错误 waitId/callId/hash 会在恢复前拒绝。

图中的 `await_input` 使用 LangGraph interrupt，等待期间进程退出，不计有效执行时间。`resume` 无补答仅用于未知异常后的 checkpoint 恢复。SQLite 保存付费请求身份和收到的响应；已收到响应可复用，SUBMITTING/UNKNOWN 不会自动重新付费提交。首版不提供强制重付命令；不确定调用需人工核查。取消只阻止本地新动作，不能撤回已发生的远端费用。

## Python 核心与目录职责

```python
from pathlib import Path
from agent_service.config import Config
from agent_service.runtime.runner import Agent

agent = Agent(Config(directory=Path('.local')))
result = await agent.send('缓存流程是什么？')
state = await agent.inspect(result['thread_id'])
# await agent.resume(thread_id, payload)
# await agent.cancel(thread_id)
```

核心使用环境变量中的凭据；dotenv 读取是 CLI 职责。模型、执行器、可用素材元数据、进度通知可注入。注入自定义 `ToolExecutor` 时必须同时传入 `assets`（以素材 ID 为键的当前版本元数据）；执行器接口仅要求 `execute()`，不读取其内部属性。`Config(limits=Limits(...))` 可调小验收预算。

| 路径 | 职责 |
| --- | --- |
| `runtime/runner.py`, `graph.py` | 公共入口、互斥、checkpoint 生命周期、10 个节点和边 |
| `runtime/state.py`, `policy.py`, `persistence.py` | 可序列化值、有限执行、SQLite 付费回执 |
| `runtime/nodes/` | 请求、决策、执行与观察、等待、结果核对 |
| `model.py`, `prompts.py` | DeepSeek 协议与有界输入、提示模板 |
| `tools/` | 工具目录、Schema、单一执行器接口、固定 Mock 样本 |
| `tracing.py` | 可选的本机 Langfuse 适配，不改变决策 |
| `analysis/` | 保留的百炼 ASR/VLM/Embedding/索引适配器，独立 MVP 不使用 |

默认每轮执行最多 12 次模型请求、20 次工具动作、3 次修正、3 次连续无进展、300 秒有效执行；单操作安全尝试最多 2 次，输出 2048 token，模型请求 64KiB，工具反馈 32KiB。补答与工具恢复沿用原执行预算；普通后续用户消息开启新执行。新需求采用新 thread，不继承旧条件。

## 验证

```sh
.venv/bin/python -m pytest -q
```

默认测试不调用任何付费模型；保留的百炼音频测试需显式 `RUN_LIVE_ASR=1` 及对应凭据、测试音频，且不属于本 MVP。真实 DeepSeek 验证用上面的无 `--script` 入口，SQLite 回执保存调用数与用量；查看 JSON 中的 trace_id 在本机 UI 定位。

设计与逐项验收结果见 [最终设计](../docs/agent-mvp-design.md) 和 [验收记录](../docs/verification/agent-mvp.md)。根项目 Compose 将旧 agent-service 放入 `agent-integration-pending` profile；尚未恢复 HTTP/MQ 协议，不应将其作为业务服务启动。
