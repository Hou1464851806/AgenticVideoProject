# Worker Langfuse 接入修复验证

2026-10-16，针对浏览器 Worker 有业务回复却没有 Langfuse trace/session 的问题。

## 原因与修复

根 Compose 未注入 Langfuse 凭据，Worker 也不走 CLI 的 dotenv 加载；运行日志明确输出“观测未启用：缺少本机 Langfuse 配置。”。另外原 Tracer 只允许 loopback 地址，容器无法使用宿主 `localhost:3035`。

- `scripts/start.sh` 在私有 `agent-service/.env.langfuse` 存在时读取它，根 `.env` 同名值优先。
- Compose 只为 Worker 注入 Langfuse 公钥、密钥及固定的 `http://host.docker.internal:3035`；观测栈数据库、管理账号等凭据不进入 Worker。
- Tracer 允许 Docker Desktop 的宿主地址，保留原本机地址及拒绝外部云服务的约束。
- 仅重建、重启 `agent-worker`；重启前数据库确认没有 RUNNING/CANCEL_REQUESTED 任务，保留原 `agent_state` 卷。Langfuse 原观测栈和业务 API 未重启。

## 检查范围及结果

本次只修改 Python 观测适配和部署配置，因此回归覆盖观测模块，以及直接调用它的 Worker、浏览器核心入口、独立 Agent 和恢复／预算逻辑。不涉及 Java、前端或跨语言契约，不执行阶段全量回归和付费验收。

| 检查 | 结果 |
| --- | --- |
| 修复前新增 Tracer 回归 | 4 通过，Docker Desktop 地址用例失败，复现地址限制 |
| `test_tracing.py`、`test_worker.py`、`test_conversation_runtime.py`、`test_agent_scenarios.py`、`test_resume_and_limits.py` | 60 通过 |
| Pyright：tracing、其测试和容器验证脚本 | 0 errors / warnings |
| 修改 Python 文件 compileall、`bash -n scripts/start.sh`、`git diff --check` | 通过 |
| Compose 解析与字段断言 | 公钥、密钥存在；地址正确；未注入观测栈基础设施凭据 |
| Worker 镜像构建及重启 | 通过，running，restart count 0 |
| 容器至 Langfuse 健康接口 | HTTP 200 |
| 容器内 `verify_worker_langfuse.py` | PASS，真实接收及查询 2 AGENT + 16 CHAIN observation |
| Java／前端回归、真实付费模型和浏览器端到端 | 未运行：本次无相应代码变更，接入验证不需要付费调用 |

验证脚本最初的模拟 Java 接口缺少 heartbeat 和固定 scope，已按真实 Worker 协议补齐，最终执行通过。代码复查确认：凭据未写入镜像或版本库；CLI 保持宿主地址；缺少配置仍明确提示；浏览器入口已有的 conversationId/session_id 映射无需修改。

## 实际上报证据

```sh
docker compose --env-file agent-service/.env.langfuse --env-file .env up -d --build --no-deps agent-worker
docker exec -i agenticvideo-agent-worker-1 python < agent-service/scripts/verify_worker_langfuse.py
```

- session ID：`1296fe42-c949-44eb-94b4-9a89f7c5d798`
- trace ID：`0580c452bec10902a548f2f78623d85e`
- trace ID：`9be4b79fd7fc4c2c49627090c92cce8e`
- 在真实 Langfuse v4 Observations API 中按 sessionId 过滤，确认两条根 observation 的 conversation_id 一致，并存在 understand_request 和 decide_next 回调。
- 模型为 ScriptedModel，Java 为 httpx.MockTransport；实际执行 Worker 的 execute_delivery、Agent、LangGraph 和 Tracer。临时 SQLite 自动删除，不修改用户业务会话，不伪造 generation。

可在[本机 Langfuse 项目](http://localhost:3035/project/agent-mvp-project/traces)按上述标识查看。历史未采集记录不会自动补回。本次网络方案适用于当前 Docker Desktop 环境。
