# Independent Python Agent MVP

M4 步骤 1～3 已接通独立的 `agent_service.preview` HTTP 客户端和五个 CLI 预览命令；Java/Python 共用契约样例。步骤 4 真实视频／故障自动验收及用户完整观看试听、步骤 5 两轴审查、Java/Python 完整非付费回归及后端打包均已通过，M4 已收尾。证据与限制见 [M4 实施记录](../docs/verification/java-tools-m4.md)。


Agent 核心负责 LangGraph 决策、条件、证据、草稿、等待、预算和恢复；Worker 适配层单独负责 MQ 与 Java 租约协议。工具通过 `ToolExecutor` 执行；可使用固定样本 Mock 或 `JavaToolExecutor` 的真实问答／剪辑草稿模式。ASR/VLM/Embedding 与索引由 Java 后端负责，旧 Python 分析适配器已删除。

## 浏览器 Agent Worker（对话生命周期第 4 步）

新版任务使用独立 `agent.v1` 队列，经 `/internal/v1/agent-runs/**` 领取固定输入，调用 `Agent.prepare_run/execute_run`，通过当前 attempt／lease／编辑代次执行工具并发布结果。Java 是业务状态权威；Python SQLite 保留原 checkpoint、execution 与付费回执。legacy Agent／Worker 开关继续关闭。草稿预览和前端卡片仍待第 5～6 步，完整记录见[第 4 步验证与交接](../docs/verification/conversation-lifecycle-step4.md)。

在根 `.env` 配置 `DEEPSEEK_API_KEY` 后，从仓库根目录执行 `scripts/start.sh`。Compose 注入 Java 地址、内部 Worker token 和 RabbitMQ 凭据；`agent_state:/state` 持久保存 SQLite。不要删除该卷、手工清理调用回执或扩容此服务；本地文件锁与 MQ exclusive consumer 保证单实例、prefetch=1。CLI 继续使用原入口及自己的数据目录。

Worker 只在 Java 持久收束结果／等待／失败／取消后 ACK。网络中断关闭未 ACK 的通道；Java 租约过期收回旧 attempt 并重新通知，原执行恢复且不重置预算。确定模型回执复用，SUBMITTING／UNKNOWN 明确停止，不自动重付。仅确有未完成恢复点且无不确定付费回执的中断标为 `EXECUTION_INTERRUPTED` 并允许浏览器显式 retry；已经结束的 INCOMPLETE、预算耗尽和未知付费提交不提供重置入口。详细命令与受控验证见交接记录。

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

进度输出到 stderr，结果 JSON 输出到 stdout。Mock 模式结果带 `mock: true`，Java 真实模式带 `mock: false`。`DRAFT_READY` 表示当前全部输出通过校验（Mock 模式为模拟校验，真实模式为 Java 校验），尚未保存或渲染；`SAVED` 表示显式保存完成。`WAITING` 正常退出；`INCOMPLETE` / `FAILED` 退出码 1。`--no-trace` 显式禁用观测。

## Java 真实问答与制作（M2 阶段 4）

先按 [后端说明](../backend/README.md)启动 Java 与基础设施，并上传素材完成所需来源分析。根 `.env` 配置 `INTERNAL_WORKER_TOKEN` 与 `DEEPSEEK_API_KEY`，凭据仅留在传输层。项目和素材 UUID 可从现有项目 API 取得；未探测／未就绪状态由真实反馈说明。

```sh
.venv/bin/python -m agent_service --java-url http://localhost:8080 --project PROJECT_UUID --asset ASSET_UUID --data-dir .local/java run '视频讲了哪些步骤？请引用原文'
.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir .local/java reply THREAD_ID '查语音步骤'
.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir .local/java resume THREAD_ID
.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir .local/java run '继续问另一个问题' --thread THREAD_ID
```

全局选项放在子命令前；`--asset` 可以重复，首次省略表示冻结该项目当前素材范围。checkpoint 保存 `question_scope`（范围身份及原元数据），补答和故障恢复 GET 原快照并核对一致性，恢复时无需重新指定项目或素材。纯问答的普通后续消息在原素材范围创建新快照，清除旧观察及覆盖后再回答。进入制作后，同需求的未保存草稿修改、补答和恢复沿用固定快照及已读证据；明确新需求建立新快照，依赖失效停止。真实需求不能切换到 Mock 恢复；有意开始不同模式使用 `--new`。

真实模式向模型开放 `list_assets/get_video_overview/search_evidence/read_video_range`，支持问答和 DRAFT 制作。DRAFT 由图强制调用 Java `validate_edit_draft`，完整结构化问题进入下一次决策；修正最多 3 次，无进展或预算耗尽返回 INCOMPLETE，保留最新草稿及问题。按需细读和新 Agent 正式导出尚未接入；保存后可使用下文的显式预览命令。`--script` 可与 `--java-url` 组合，表示脚本决策读取真实证据，不能称为真实模型验收。不会创建后台等待任务或接受 `complete-mock/tool-result`。

语音引用保留原区间、音轨时间和上下文；视觉引用保留 `POINT/observed_at_ms`，不会制造1ms区间。素材分页累积前页，目录读完与分析覆盖分别保存。来源未就绪、权限、依赖失效和执行不确定直接收尾；只有参数错误进入有限修正。工具传输中断不自动重试，明确输出 `INCOMPLETE`，不能当作空结果。

从仓库根目录执行第四阶段专用联调（使用清单中的已有素材；查询可能产生 Embedding 费用，不重新执行 ASR/VLM）：

```sh
agent-service/.venv/bin/python agent-service/scripts/verify_java_agent.py --samples docs/verification/java-tools-m1-stage4-samples.json --data-dir agent-service/.local/stage4-scripted --output agent-service/.local/stage4-scripted.json --no-trace
agent-service/.venv/bin/python agent-service/scripts/verify_java_agent.py --samples docs/verification/java-tools-m1-stage4-samples.json --data-dir agent-service/.local/stage4-deepseek --output agent-service/.local/stage4-deepseek.json --deepseek
agent-service/.venv/bin/python agent-service/scripts/verify_java_resume.py --input agent-service/.local/stage4-scripted.json --data-dir agent-service/.local/stage4-resume --output agent-service/.local/stage4-resume.json
```

清单 ID 只适用于本次本机数据，其他环境须显式替换项目与素材，失效不会自动改用其他范围。脚本模型四视频／真实模型两视频／跨进程补答恢复与观测验证分别记录在 [第四阶段记录](../docs/verification/java-tools-m1-stage4.md)，不能代替第五阶段完整 M1 验收。

## 本机 Langfuse

```sh
.venv/bin/python scripts/setup_langfuse.py
docker compose --env-file .env.langfuse -f compose.langfuse.yaml up -d
curl -f http://localhost:3035/api/public/health
```

打开 <http://localhost:3035>。账号和密码见私有 `.env.langfuse` 的 `LANGFUSE_INIT_USER_EMAIL` / `LANGFUSE_INIT_USER_PASSWORD`；初始化脚本不打印凭据，已有文件不会覆盖。CLI 自动加载这个文件。独立数据卷与项目业务设施隔离，Web 仅绑定本机 3035，样本对象存储仅绑定本机 9095。镜像已按本次验证的 digest 固定，Langfuse 服务版本 4.59.0。

浏览器 Worker 使用 Docker Desktop 的 `http://host.docker.internal:3035` 访问本机观测栈。根目录 `scripts/start.sh` 在私有 `agent-service/.env.langfuse` 存在时自动读取它，再读取根 `.env`（同名配置以后者为准）；仅向 Worker 注入 Langfuse 公钥、密钥和容器地址，不传入观测栈的数据库或管理账号凭据。未配置密钥时观测仍为可选，并在 Worker 日志明确提示。已有观测栈启动后，只重建 Worker 可在仓库根目录执行：

```sh
docker compose --env-file agent-service/.env.langfuse --env-file .env up -d --build --no-deps agent-worker
```

浏览器请求的理解与执行片段使用业务 `conversationId` 作为 Langfuse session ID，多个 trace 归入同一会话。配置生效前未采集的请求不会自动补回。

重启后可从仓库根目录运行非付费验证。该脚本在 Worker 容器内运行实际 `execute_delivery`，模拟 Java 接口和模型，使用临时 SQLite，并通过真实 Langfuse Observations API 核对理解／执行两条 trace、LangGraph callback 及同一 session 关联；不会修改业务会话或调用付费模型：

```sh
docker exec -i agenticvideo-agent-worker-1 python < agent-service/scripts/verify_worker_langfuse.py
```

每次运行或恢复生成一个 trace，使用相同 execution_id 关联恢复片段；LangGraph callback 记录节点，真实 DeepSeek 调用记录 generation 和 token，执行器边界记录带当前 `mock` 标记和调用身份的工具 observation。脚本模型不会伪造 generation。Langfuse 不可用会有终端警告，Agent 仍使用 SQLite 正常运行。

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

默认每轮执行最多 12 次模型请求、20 次工具动作、3 次修正、3 次连续无进展、300 秒有效执行；单操作安全尝试最多 2 次，输出 2048 token，模型请求 64KiB，工具反馈 32KiB。补答与工具恢复沿用原执行预算；普通后续用户消息开启新执行。新需求采用新 thread，不继承旧条件。

## 验证

```sh
.venv/bin/python -m pytest -q
```

默认测试不调用任何付费模型；百炼真实验收统一使用根目录 Java 验收入口。真实 DeepSeek 验证用上面的无 `--script` 入口，SQLite 回执保存调用数与用量；查看 JSON 中的 trace_id 在本机 UI 定位。

设计与逐项验收结果见 [最终设计](../docs/agent-mvp-design.md) 和 [验收记录](../docs/verification/agent-mvp.md)。根项目 Compose 已删除旧 agent-service 服务；Python 通过本机 CLI 调用 Java HTTP 读取工具。

## M1 综合验收

在仓库根目录执行，先启动 Compose 并配置百炼、DeepSeek 与内部凭据：

```sh
agent-service/.venv/bin/python agent-service/scripts/verify_java_m1.py --output agent-service/.local/stage5-new
```

此命令创建新项目、上传四个固定视频，执行真实 Java 分析和索引、开发/保留语义查询、四工具读取及四次真实 DeepSeek 问答；会发生模型费用。缺凭据返回 `UNVERIFIED` / 退出码2。输出保留范围、模型回执及用量、工具请求与结果、最终 JSON 和 trace ID。需要 PostgreSQL Compose 服务以导出权威回执。

实际 Java 进程故障验收另行执行（会终止并重启本机 business-api，保留数据卷）：

```sh
agent-service/.venv/bin/python agent-service/scripts/verify_java_crash.py
```

完整结果与未验证边界见[第五阶段记录](../docs/verification/java-tools-m1-stage5.md)。

## M2 阶段 1：制作数据契约

已落实 [M2 设计](../docs/java-tools-m2-design.md) 的契约与制作条件；阶段 2～3 实现 Java 校验和原子保存，阶段 4 接通 Python 真实制作、修正、固定快照和显式保存。受控验证见 [阶段 4 记录](../docs/verification/java-tools-m2-stage4.md)，真实 Agent 素材验收见 [阶段 5 记录](../docs/verification/java-tools-m2-stage5.md)。

`agent_service/editing.py` 定义独立的草稿、逐输出条件、校验输入／问题／结果、制作身份和保存请求／结果。`runtime/state.py` 将理解增量合并为完整条件：程序生成 `out-1` 等稳定 `output_key`，单输出修改按键定位；标题和草稿顺序不影响条件关联。默认单输出、横屏、字幕开启、不强求完整字幕，不增加默认硬时长。`requirements.sources` 记录默认值或用户消息身份及原文，随 checkpoint 持久保存，CLI/status 可查看。

调用 Java 时只传规范化条件，不包含运行模式和条件来源；未显式限定素材时，由程序展开固定素材上下文。`validate_edit_draft` 的参数只有 `draft/requirements/draft_hash/requirements_hash`；不再携带 `read_ids/read_records`。模型只能提出编排，不能携带项目／需求／scope 等可信身份，不能提出字幕或业务标识。保存请求使用独立的 CLI `save` 命令提交，不暴露给模型。

跨语言样例位于 `backend/src/test/resources/contracts/m2-editing.json`，两侧均验证中文、换行、emoji 的紧凑 UTF-8 键排序 SHA256，以及严格类型、输出键对应、数量、时长和大小限制。阶段记录见 [M2 阶段 1](../docs/verification/java-tools-m2-stage1.md)。

## 显式保存当前制作

```sh
.venv/bin/python -m agent_service --java-url http://localhost:8080 --project PROJECT_UUID --asset ASSET_UUID --data-dir .local/java run '生成剪辑草稿'
.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir .local/java run '第二条改为20秒' --thread THREAD_ID
.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir .local/java save THREAD_ID
.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir .local/java status THREAD_ID
.venv/bin/python -m agent_service --java-url http://localhost:8080 --project PROJECT_UUID --data-dir .local/java run '开始新制作' --thread THREAD_ID --new
```

修改示例适用于已有多输出草稿。默认一个输出、横屏 720p、30fps、字幕开启；未指定时长不增加硬时长。JSON 的 `requirements.sources` 展示用户原文与默认来源，`validation.durations_ms` 按草稿输出顺序展示实际区间总时长，`validation.warnings` 披露字幕缺口等限制；`draft/validation` 在未完成时仍可检查。

只有当前 DRAFT_READY 可以保存。草稿或条件修改后必须重新校验；所有输出整体保存，不能只保存成功部分。保存命令不调用模型，不要求 DeepSeek 凭据，返回 `saved_result` 中的 `plan_id/revision_id/outputs[].output_id`。保存不触发确认、预览或渲染。

保存前先将当前校验和来源绑定持久化。HTTP 断开或提交结果未知时，保留失败并停止；再次执行相同 `save THREAD_ID`（或 `resume THREAD_ID`），先查询 Java 的权威保存结果，已经提交则恢复同一版本，不依赖原素材仍可用。结果未核对时 status 返回 INCOMPLETE；拒绝继续编辑，请先运行 save 或 resume。已保存制作只读，重复 save 返回原结果；明确新制作使用 --new 创建新方案，不派生旧版本。

跨语言受控闭环由 `DraftValidationIntegrationTest#pythonAgentRepairsRealValidationThenExplicitCliSaves` 自动启动隔离 PostgreSQL 与真实 Java HTTP，再调用本机 `agent-service/.venv/bin/python`。此用例覆盖错误草稿、补读、修正、校验发布后的进程故障恢复、多输出局部编辑与显式 CLI 保存；输出证据位于 `backend/target/m2-stage4-controlled.json`，不调用付费模型。

## M2 真实素材验收

复用 M1 已分析素材，从仓库根目录运行，配置 DeepSeek 和内部凭据并启动当前 Java 服务：

```sh
agent-service/.venv/bin/python agent-service/scripts/verify_java_m2.py --case multi --output agent-service/.local/m2-acceptance-multi
```

可选 case 为 `single`（默认条件）、`single-multi`、`multi-single`、`multi`（两条制作，第二条缩短为严格4秒、改名并重排）、`visual`（竖屏／关闭字幕）和 `impossible`（60秒不可满足）。默认清单为 M1 阶段5已有素材；其他数据库须显式传入 `--samples`，不会自动换素材。每次必须使用新输出目录，命令可能产生 DeepSeek 和查询 Embedding 费用，不上传或启动基础分析。

每轮使用真实 CLI 子进程，保存完整结果、stderr、SQLite 模型回执及 PostgreSQL 工具／校验／版本来源到 `evidence.json`。生成后不自动保存是 Agent 的行为；本验收命令代替用户显式运行 CLI save，并核对重复保存和跨进程 resume 的同一版本身份。生产使用时请先查看草稿再运行 save。`impossible` 必须未完成且无版本；模型可能在提出草稿前说明不可满足，此时不会伪造 Java 校验回执。

读取进度中的 `_range_reads.complete` 只表示固定范围的已有记录分页已完整消费，与稀疏分析覆盖不同。修正反馈会明确拒绝多个结构化调用，模型仍可能用尽预算或算错时长；失败保留且不能保存。本轮成功和失败样本、确定性边界验证及媒体回归分别见阶段5记录。


## 显式预览已保存方案（M4 步骤 3）

在仓库根目录运行；只需 Java 服务和 `INTERNAL_WORKER_TOKEN`，不需要 DeepSeek 凭据。先设置模块路径：

```sh
export PYTHONPATH=agent-service
```

```sh
agent-service/.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir agent-service/.local/java preview THREAD_ID
agent-service/.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir agent-service/.local/java preview THREAD_ID --output out-1
agent-service/.venv/bin/python -m agent_service --java-url http://localhost:8080 --data-dir agent-service/.local/java preview-list THREAD_ID
agent-service/.venv/bin/python -m agent_service --java-url http://localhost:8080 preview-status REQUEST_ID
agent-service/.venv/bin/python -m agent_service --java-url http://localhost:8080 preview-cancel REQUEST_ID
agent-service/.venv/bin/python -m agent_service --java-url http://localhost:8080 preview-retry REQUEST_ID
```

也可进入 `agent-service` 后使用 `.venv/bin/python` 和相应 data-dir。全局参数放在子命令前。

`preview` 和 `preview-list` 使用原制作的 data-dir／THREAD_ID。只有一条输出时可省略 `--output`，多条时指定保存结果中的 `output_key`；选择由 Java 权威保存结果校验。未提交保存的草稿不能预览。保存已经提交但本地丢失响应时，命令只读查询 Java 保存结果，不自动保存、不写回 checkpoint。Mock／无真实制作身份的 thread 被拒绝。

`preview-status/cancel/retry` 直接使用 REQUEST_ID，不创建 Agent、不读取本地 SQLite。提交立即返回 request／task 身份，不等待渲染；列表找回服务器已接纳请求，详情返回实际任务状态、独立产物状态及有效期 300 秒的播放链接。链接到期后重新查询，不重新渲染；命令不自动下载或打开播放器。

普通重复提交复用原请求／任务或可用产物；失败、取消或产物失效需显式 `preview-retry`。重复 retry(A) 总是核对同一后继 B，B 再失败则 retry(B)。通信中断不自动重试 POST、不伪造 Java 任务失败；普通提交用 `preview-list THREAD_ID` 找回，重试可再次对原 REQUEST_ID 执行 retry 核对固定后继。

取消影响共享的实际渲染任务及所有复用请求，`CANCEL_REQUESTED` 仅表示已请求停止，`CANCELLED` 才表示确认取消；成功终态取消不会删除文件。预览命令不进入模型决策、不改变 SAVED／waiting／预算；退出 CLI 不取消 Java 任务。

stdout 输出协议 JSON，提示写入 stderr。成功接纳、运行中查询、列表及合法取消退出 0；提交／查询／重试取得 FAILED、CANCELLED 或 UNAVAILABLE／REMOVED 产物退出 1；参数／认证／HTTP／网络／协议错误非零，Ctrl-C 为 130。预览访问错误输出 `error`，不伪装成 Java 的任务状态；原有制作命令退出码保持。

### M4 真实媒体与故障验收

在仓库根目录使用已保存的 M2 验收目录；脚本不上传、不分析、不调用模型、不保存新方案。使用新输出目录保留原始日志：

```sh
agent-service/.venv/bin/python agent-service/scripts/verify_java_m4.py \
  --source agent-service/.local/m2-stage5-single-accepted \
  --output agent-service/.local/m4-videos-new
```

脚本显式轮询、获取播放地址并下载核验规格、时长、发布哈希和字节数；CLI 本身仍不等待或下载。需要本机 ffmpeg／ffprobe、当前 Compose 和源目录下的 runs.json／agent/。视频内容还需完整观看试听。

真实故障实验会中断本机 Java、RabbitMQ 和 Silo；在无其他排队／运行任务时单独执行，选择尚无预览历史的已保存输出：

```sh
agent-service/.venv/bin/python agent-service/scripts/verify_java_m4_faults.py \
  --source agent-service/.local/m2-stage5-single-multi --output-key out-2 \
  --output agent-service/.local/m4-faults-new
```

该示例的输出已参与本机验收，重跑须选另一条未预览的已保存输出；脚本会明确拒绝历史输出。故障期间保留数据卷和原始素材，备份后移走预览对象，退出时恢复服务、对象及容器内临时 FFmpeg 包装。raw stdout 中的临时播放 URL 只留在忽略的本地目录；分享记录前去除签名和凭据。实际证据与未完成项见 [M4 步骤 4](../docs/verification/java-tools-m4.md#步骤-4真实视频与故障联调)。

## 会话生命周期核心（实施顺序第 2 步）

新版适配层调用 `Agent.prepare_run(run_input)`，输入为步骤 1 的严格 `RunInput` 对象或 camelCase JSON。它只理解当前消息，返回核心视图中的 `parsed_input`（Python snake_case）和用量；`PARSED` 是本地交接结果，不是公开 `RunResult`。需要 wire 值时使用 `ParsedInput.model_validate({"mode": parsed["mode"], "relation": parsed["relation"], "questionId": parsed["question_id"], "conditionsPatch": parsed["conditions_patch"]}).model_dump(by_alias=True)`。模型必须明确关系，缺失或无效关系在原预算内有限修正；不能根据普通消息自动消费待回答问题。

Java 核对关系后，调用 `Agent.execute_run(run_input, identity=identity, scope=scope, production=production, edit_generation=generation, question=question)`。`identity`／`question` 使用步骤 1 的对象或 camelCase JSON；scope 与 production 沿用现有 Python／M2 snake_case 结构，production 含 `project_id/requirement_id/scope_id`。真实执行必须提供 Java 固定的完整 scope；新制作还须由 Java 先确定 production。这里不调用旧 CLI bind 来创建浏览器制作。

- 独立 QUERY／DISCUSS 使用本次接纳的 thread，不写当前制作 checkpoint；公开历史只来自固定输入，当前原文单独出现。普通讨论可无证据，视频事实问答继续校验已读来源。
- 继续制作使用制作 thread、新 execution 和 Java 给定代次；关联补答使用明确的 question／wait 和原 thread／execution。补答先完成自己的入口理解，再幂等归集调用数与活动耗时，复用原图的工具、修正和调用序号；等待时间不计入活动耗时。
- `conversation_runs` 在现有 SQLite 中保存固定输入、不可变绑定、执行起始值和本地理解／结果；`execution_transfers` 保证预算归集仅一次。重投不重新 send、不拼最新聊天、不重置预算。原模型回执保留原身份；SUBMITTING／UNKNOWN 仍停止自动重付。
- QUERY／DISCUSS 不合并制作条件、不清空旧校验；发起制作修改后，即使失败再插入问答，也不会重新开放旧稿保存。CLI 已保存制作可继续问答／讨论，修改仍只读；显式新制作保留原 `--new` 用法。

这些入口返回核心视图，不负责 Java 权限、attempt／lease、编辑代次写入授权、scope 回执发布或 `RunResult` 投影；后续步骤 3／4 接入这些事务与传输。浏览器制作不能调用 CLI `save`，仍须由 Java 的接受事务冻结与确认。当前未开放新版浏览器路由或 Worker，也没有新增付费调用。验证及下一步接缝见[步骤 2 记录](../docs/verification/conversation-lifecycle-step2.md)。
