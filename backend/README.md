# Java 后端

M4 步骤 1 提供 `POST /internal/v1/productions/{requirement_id}/previews`（JSON `{}` 或 `{"output_key":"out-1"}`）及同路径 GET 列表，使用现有 `INTERNAL_WORKER_TOKEN` Bearer 认证。仅接受已保存制作，由服务端解析版本与输出，新建 202、复用 200。步骤 2 已增加 `GET /internal/v1/previews/{request_id}`、`POST /internal/v1/previews/{request_id}/cancel` 和 `/retry`。详情实时核对产物并生成 300 秒播放链接；普通提交不会自动重做失败／取消／失效的预览，显式重试始终返回原请求的固定后继。存储异常返回 503，不改写为文件失效。步骤 3 已接通 [Python CLI 五个预览命令](../agent-service/README.md#显式预览已保存方案m4-步骤-3)，不使用模型；步骤 4 已通过真实视频／故障自动验收，用户已确认五条视频完整观看试听无问题；步骤 5 两轴审查、Java/Python 完整非付费回归及后端打包已通过，M4 已收尾。完整范围和验证见 [M4 实施记录](../docs/verification/java-tools-m4.md)。


M1 已落地单体媒体执行、Java 百炼基础分析与索引，以及第三阶段问答快照、四个真实读取工具、稳定分页和独立通道降级。Python `JavaToolExecutor` 已在第四阶段接入；真实上传到问答、故障与媒体回归见[第五阶段综合记录](../docs/verification/java-tools-m1-stage5.md)，未运行项目单独列明。完整范围见 [最终设计](../docs/java-tools-m1-design.md)，媒体阶段验证见 [记录](../docs/verification/java-tools-m1-stage1.md)，分析阶段见 [记录](../docs/verification/java-tools-m1-stage2.md)，工具阶段见 [记录](../docs/verification/java-tools-m1-stage3.md)。

M2 已增加真实草稿校验、显式整体保存及制作来源；Python CLI 可制作、修正、调整未保存草稿并保存方案。真实素材制作、约束失败、版本消费兼容和本轮回归见 [M2 阶段5记录](../docs/verification/java-tools-m2-stage5.md)。保存不触发确认、预览或导出，旧浏览器 Agent 入口仍不可用。

## 结构与职责

```text
backend/
├── README.md
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/com/agenticvideo/business/
    │   ├── bailian/    # 三个具体模型协议客户端与有界 HTTP 传输
    │   ├── analysis/   # 基础分析步骤、回执和原始观察发布
    │   ├── search/     # 派生单元、Embedding、索引与固定范围的权威证据读取/混合排名
    │   ├── tools/      # 可信问答快照、四工具参数/回执/分页与内部 HTTP 入口
    │   ├── media/      # FFmpeg、探测、音频、稀疏帧、渲染、临时目录与媒体存储
    │   ├── messaging/  # durable 队列、确认发布、消费与 ACK
    │   ├── task/       # 原子占用、执行代次、取消、结果与重启恢复
    │   └── ...        # 上传、认证、项目、证据、方案与产物等既有业务
    ├── main/resources/ # 应用配置与 Flyway 数据库定义
    └── test/java/      # 真实基础设施集成验证
```

根 Maven 聚合入口仅包含 `backend`，不再有 `business-api/` 和 `media-worker/` 子工程。Compose 保留服务名 `business-api` 以复用前端地址和已有脚本，该容器运行整个 Java 后端。独立媒体容器及媒体 HTTP 客户端、控制端点、线程池和 Worker 心跳已删除。

`MediaProcessing.execute` 负责受控媒体操作、对象读写与临时目录；不理解 RabbitMQ 或任务状态。`LocalTaskRunner` 在消费线程执行阶段，`TaskExecution` 原子接纳并验证当前代次后发布业务结果。渲染沿用冻结版本、原素材、确认、产物身份及播放规则。

## 构建与运行

在仓库根目录执行；JDK 17、Maven 与 Docker 必须可用。新媒体集成测试在宿主机运行 FFmpeg/ffprobe，需安装它们。

```sh
mvn -B -ntp verify
./scripts/start.sh
```

单独运行本阶段集成验证：

```sh
mvn -B -ntp -pl backend -am test \
  -Dtest=LocalMediaIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Compose 使用 `backend/Dockerfile` 构建前端和后端，运行镜像包含固定 FFmpeg 与 Noto CJK 字体。媒体工作目录为可写 `/work`，继续使用 `worker_work` 卷；空间核算读取该同一目录。数据库、Silo、RabbitMQ 和 Qdrant 持久卷保留，启动脚本不会删除旧数据。

Silo S3 API 使用本机 9000 端口，WebUI 固定为 [http://localhost:9001](http://localhost:9001)，两者仅绑定 `127.0.0.1`。WebUI 用户名为 `agentic-storage`，密码为根目录 `.env` 中的 `STORAGE_SECRET_KEY`。调整启动参数或端口映射后执行 `docker compose up -d --no-deps silo` 应用配置，保留 `silo_data`；单纯 restart 不会更新容器配置。

## 任务与 RabbitMQ

```text
业务事务保存任务 + Outbox
→ mandatory 持久消息 + publisher confirm + 无退回
→ media.v1 / analysis.v1 有界消费者原子占用数据库任务
→ 直接执行阶段，保存结果或下一次重试意图
→ ACK；终态失败 Reject 到对应 media.dead.v1 / analysis.dead.v1
```

媒体消费者固定并发 1、prefetch 1，消费线程直接执行，不先 ACK 再移交线程池。消息重复投递由任务状态和原子占用挡住；终态重放复用已保存结果。安全媒体临时失败最多三次，重试意图经 Outbox/MQ 派发，任务的下次执行时间挡住旧消息提前启动重试。数据库仍保存失败原因。

Outbox 发布失败按 2–60 秒有界退避保留意图，只有确认路由成功并获得发布确认才标记 `PUBLISHED`。RabbitMQ 不可用不会丢弃数据库任务。消费者 ACK 与 publisher confirm 是不同确认，详见 [RabbitMQ 官方说明](https://www.rabbitmq.com/docs/confirms)。

单实例用进程内活跃集合识别执行者；数据库保存执行代次和取消状态。本机尝试不使用 Worker token、租约或心跳。周期核对不再活跃的运行代次，将安全媒体阶段重新接纳到 Outbox，拒绝废弃代次的迟到结果；取消后的 FFmpeg 在退出后才结束任务。临时目录在执行结束清理，残留目录在启动时回收。此机制仅用于单实例。

## 当前配置与能力边界

| 配置 | 当前作用 |
| --- | --- |
| `TASKS_DISPATCH_ENABLED` | 开启 Outbox 派发及恢复，Compose 为 true |
| `TASKS_CONSUME_ENABLED` | 开启本机媒体/分析消费者，默认跟随派发开关 |
| `ANALYSIS_CONSUMER_CONCURRENCY` | 分析消费者并发，初始 2；prefetch 1 |
| `ASR_QUERY_SECONDS` | 异步查询间隔，默认 5 秒 |
| `DASHSCOPE_API_KEY`、`DASHSCOPE_BASE_URL` | 百炼凭据与 DashScope API v1 地址，默认北京公共域名 |
| `MEDIA_WORK_DIRECTORY` | 本机媒体工作目录；Compose 为 /work |
| `DATABASE_*`、`STORAGE_*`、`RABBITMQ_*` | 沿用数据库、Silo 与 RabbitMQ 配置 |
| `LEGACY_WORKER_ENABLED` | 默认 false；旧 HTTP Worker 协议返回 410 |
| `LEGACY_AGENT_ENABLED` | 默认 false；旧浏览器 Agent 任务入口不可用 |
| `LEGACY_INSPECTION_ENABLED` | 默认 false；按需细读尚未接入 |
| `ANALYSIS_EXECUTION_ENABLED` | 单独启动默认 false，Compose 默认 true；上传后自动基础分析 |

上传完成后经媒体队列保存探测、全音轨和稀疏帧，再自动接纳语音和基础视觉任务。无音轨 ASR 为 `NOT_APPLICABLE`；帧提取失败只影响视觉。默认模型为 `qwen3-asr-flash-filetrans`、`qwen3-vl-plus` 和 `text-embedding-v4`（1024维）。ASR 使用私有临时上传地址，VLM 只接收校验过的 JPEG，Embedding 分批至多10条。

`AnalysisProcessing` 执行三个具体分析流程，不包含 MQ 或权限规则；调用 `TaskExecution` 的短事务入口登记和保存回执。模型和 Qdrant 请求均在事务外。`SearchIndexBuilder` 只写可重建索引，`SearchIndexes` 从 Qdrant 回读核对单元、向量及可查询性后才发布代次。

ASR 等待保存远端任务身份、结束当前步骤并 ACK，下次查询经 Outbox/MQ 派发；正常等待不计入三次失败上限，30分钟后停止自动查询并保留远端身份。安全读取或索引写入临时失败最多三次。提交结果未知时停止自动重付；模型完整响应先持久化再解析，解析失败保留响应和用量。重复消息、重启或重建复用成功模型结果及向量。语音区间保留原音轨偏移；视觉证据保存真实观察点，稀疏覆盖始终 `complete=false`。

分析失败终态保存数据库原因并 Reject 到 `analysis.dead.v1`。单实例恢复核对废弃代次：已有远端身份继续查询，已保存响应继续解析，没有响应的 `SUBMITTING` 调用转为 `UNCERTAIN` 并停止重付。取消后仍可保存已发生请求的回执，但不能发布证据或提交新动作。
旧任务 HTTP 请求类型与部分业务实现暂留，用于明确开启 legacy 配置的历史契约回归；新媒体执行不经过这些 HTTP 路径。Python 旧分析适配器和旧服务已删除；这些 Java 夹具仅保留尚未重做的历史业务规则回归。Legacy 开关不是受支持的运行模式。

## 问答快照与真实读取工具

本机 CLI 使用 `INTERNAL_WORKER_TOKEN` 对 `/internal/v1/questions/**` 发起 Bearer 请求；复用内部安全链，不要求开启 legacy Worker。该凭据只在传输层使用。浏览器会话无法创建内部范围，模型参数不能携带项目、范围或认证字段。

| 入口 | 请求/结果 |
| --- | --- |
| `POST /internal/v1/questions/scopes` | `{ "project_id": "UUID", "asset_ids": ["UUID"] }`；素材列表可省略，返回201及 `scope_id/project_id/assets/mock=false` |
| `GET /internal/v1/questions/scopes/{scope_id}` | 读取原快照并核对必要依赖；恢复时使用此入口，不能重新读取最新 heads |
| `POST /internal/v1/questions/scopes/{scope_id}/tools` | 接收 `call_id/tool_name/arguments/request_hash`；直接返回 Python `ToolResult` 字段，无浏览器 data 外层包装 |

快照在 PostgreSQL 的 repeatable-read 短事务中保存素材元数据、分析版本、就绪来源、实际采样与语音覆盖、索引代次及可见证据/单元身份。晚发布的证据不会进入原快照；新普通消息重新创建快照，补答/重启沿用原身份。素材哈希、有效性、原分析/已发布索引或证据依赖失效返回明确错误，不切换当前 heads。未探测素材可以列出，其时长和来源保留未就绪状态。

| 工具 | 参数（`cursor` 均为可选非负整数，默认0） |
| --- | --- |
| `list_assets` | `cursor` |
| `get_video_overview` | `asset_id, cursor`；返回 `data.entries`，仅用于导航 |
| `search_evidence` | `query, source_kinds, asset_ids, cursor`；来源默认 SPEECH，素材默认快照全部范围 |
| `read_video_range` | `asset_id, start_ms, end_ms, cursor`；按半开区间读取快照原始证据，不调用向量服务 |

请求哈希与 Python 一致：对 `{ "tool": tool_name, "args": arguments }` 使用键排序、紧凑 JSON 和 UTF-8 SHA256；哈希包含请求原样参数（含 cursor），不能先补默认字段后核验。检索排名的条件身份单独规范化素材/来源排序和默认值，并排除 cursor。相同范围与调用身份内容不同返回 `CALL_ID_CONFLICT`；完成重放复用持久反馈；未完成回执返回 `CALL_UNCONFIRMED`，不自动重执行。没有旧每任务12次工具限制，Python 沿用现有模型／工具预算。

工具反馈最大32768 UTF-8字节，`data.next_cursor=null` 表示页尾；调用方需累积前页。`coverage.page_complete` / `navigation_complete` 表示到达当前集合末尾，只有消费全部页面才可视为目录读完，不能据此判断分析完整。单条记录及元数据过大明确报错，不截断原文。语音保留原区间、音轨时间与字词观察；视觉返回 `observed_at_ms/time_kind=POINT`，不制造正长度区间。覆盖中的稀疏视觉始终不完整。

检索各通道独立执行，Qdrant 在召回前按快照索引代次及各素材已就绪来源过滤，再从 PostgreSQL 回查快照证据。每通道至多50个候选，融合排名至多100个单元；`candidates_truncated/channel_limit/candidate_limit` 与实际来源缺口分别返回。`data.channels` 报告 DENSE/BM25/LITERAL 状态，Embedding 或 Qdrant 失败时仍返回可用通道依据。排名、查询提交身份、响应、向量及用量持久保存；翻页和同条件新调用复用排名，不重复付费。同模型/维度一次提交失败后，该查询其他分组也不再次提交。付费结果不确定标为 UNCERTAIN，不能通过重放恢复付费。

未就绪返回 `ERROR/SOURCE_NOT_READY` 并保留来源状态；部分来源就绪则 `READY` 返回已有依据和缺口。四读取工具不启动分析、不返回需自动等待的 PENDING。权限、快照失效、身份冲突、未核对执行或费用不确定都不属于参数修复；调用方应结束本次尝试或说明缺口。空匹配只表示当前快照和有界召回未命中。

第三阶段受控契约验证：

```sh
mvn -B -ntp -pl backend -am test \
  -Dtest=ReadToolsIntegrationTest,ToolJsonTest -Dsurefire.failIfNoSpecifiedTests=false
cd agent-service
.venv/bin/python -m pytest tests/test_java_read_tool_contract.py tests/test_tool_contracts.py -q
```

## 验证入口与记录

最终设计到实现的逐项核对、审查修复与提交前回归见[收尾审查](../docs/verification/java-tools-m1-closeout.md)。

Python `JavaToolExecutor`、真实模式能力目录、点证据语义及补答/恢复已接入，CLI 命令见 [Python README](../agent-service/README.md)，本阶段验证见 [第四阶段记录](../docs/verification/java-tools-m1-stage4.md)。

第二阶段固定四样本的百炼真实验收已通过，具体边界见阶段记录。第三阶段四工具验证见工具阶段记录；Python 问答、补答及恢复已完成第四阶段验证；综合验收和未验证边界见[第五阶段记录](../docs/verification/java-tools-m1-stage5.md)。历史 Python 模型脚本和依赖独立 Worker 的浏览器故障脚本不能充当 M1 验收；阶段记录分别报告真实基础设施运行与未运行项目。

- [M1 最终设计](../docs/java-tools-m1-design.md)
- [ADR-0017](../docs/adr/0017-move-baseline-analysis-and-reading-tools-into-java.md)
- [Python Agent 说明](../agent-service/README.md)
- [方案与渲染契约](../docs/edit-plan-contract.md)

真实分析与工具验证入口（仓库根目录）：

```sh
mvn -B -ntp -pl backend -am test -Dtest=LocalAnalysisIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
node scripts/test-models.mjs
```

后一个命令明确执行付费模型和四工具 HTTP 验收，读取环境或根 `.env` 中的百炼凭据，用临时真实基础设施上传四个固定视频。缺少凭据或测试被跳过返回 `UNVERIFIED` / 退出码2，不报告成功。普通 Maven 回归不调用付费模型。

从全新上传贯通 Python 四工具和 DeepSeek 的付费验收：

```sh
agent-service/.venv/bin/python agent-service/scripts/verify_java_m1.py --output agent-service/.local/stage5-new
```

另需 `DEEPSEEK_API_KEY` 与本机 Python 虚拟环境。该脚本保存新素材范围、百炼回执、语义查询、四工具反馈、DeepSeek回答与 trace；它与只运行 Java 的 `test-models.mjs` 分别报告。实际SIGKILL恢复及单体媒体浏览器回归入口见第五阶段记录；服务重启后浏览器须重新登录。

## M2 阶段 1：方案契约与来源

`business/plan/EditDraftContracts.java` 定义 M2 跨语言值与边界解析，不执行证据校验或保存。与 Python 共用测试样例，限制 1～10 输出、每输出 1～100 片段，整数使用非负／正数的有符号 64 位范围；文本长度按 Unicode 码点计。校验参数最多 65536 UTF-8 字节，校验和保存结果最多 32768 字节。可省略的输出时长上／下限规范化为 null；字段类型不做字符串、布尔或浮点到整数的隐式转换。

V52 新增 `edit_productions` 和 `edit_draft_validations`：制作需求绑定项目及固定 scope；校验绑定需求、原工具回执、完整条件／草稿、来源、两种哈希、规则版本和规范化内容。Python `identity.requirement_id` 对应 `edit_productions.id`，`question_scope.scope_id` 对应其 `scope_id`。`plan_revisions` 保留旧任务来源，并支持独立制作来源：二者必须择一；新来源必须引用本需求、本项目的校验，并与保存结果（含 output_key 映射）同一行提交。同需求唯一约束为后续幂等保存提供基础。

本阶段只交付类型和数据库约束，没有开放校验或保存 HTTP 路由，也没有贯通新 Agent 到既有媒体制作。实际阶段验证见 [M2 阶段 1](../docs/verification/java-tools-m2-stage1.md)。

## M2 阶段 2：真实草稿校验

内部 Bearer 认证继续使用上述凭据。先创建固定 scope，再由可信客户端将制作需求 UUID 绑定到该 scope：

| 接口 | 契约 |
| --- | --- |
| `PUT /internal/v1/questions/productions/{requirement_id}` | `{ "project_id": "UUID", "scope_id": "UUID" }`；绑定可重放，同身份不能更换项目／scope |
| `POST /internal/v1/questions/productions/{requirement_id}/tools` | `call_id/tool_name/arguments/request_hash`；仅接纳 `validate_edit_draft`。参数为阶段 1 的完整 `draft/requirements/draft_hash/requirements_hash`；制作身份来自路由和已存关联 |

读取仍使用该 scope 的四个工具入口。校验只采信服务端成功 `read_video_range` 回执，核验从 cursor=0 到末页的完整链；完整范围可相接覆盖片段。检索／目录不替代范围阅读。新 head 不改变固定分析版本；真实依赖失效返回停止类错误。scope 现在同时固定音轨存在性、源坐标偏移与音轨范围，用于切词、字幕映射和 ASR 覆盖检查。旧 scope 若缺少这些固定媒体信息，制作入口明确停止，需用户明确启动新需求并创建新 scope；不会自动更新旧快照。

校验成功执行返回 `READY`；`data.valid=false` 表示草稿不合规，issues 含字段路径和 `output_key`。返回实际源区间时长、两种哈希、`validation_id` 和必要警告；超过反馈上限明确设置 `issues_truncated`，警告省略以 `WARNINGS_TRUNCATED` 计数。规范化内容只存于数据库，不进入模型反馈，不分配永久 outputId/clipId。校验记录与工具结果在短事务内同时写入，相同调用重放返回原结果；提交故障上抛，未核对回执明确停止。

方案规则在 `business/plan/EditDraftValidation`，服务端证据／读取依据在 `DraftEvidence`，制作绑定与校验持久化在 `ProductionDrafts`；工具入口复用现有身份、哈希与回执治理。旧方案入口共用 `SpeechTimings` 的原文选词和边界规则。本阶段不创建方案版本、不调用 ASR/VLM，也没有接入 Python 的真实 DRAFT 或 CLI 保存。验证记录见 [M2 阶段 2](../docs/verification/java-tools-m2-stage2.md)。


## M2 阶段 3：显式保存与版本读取

保存仍走内部 Bearer 认证，不是模型工具。先取得本需求 `validate_edit_draft` 的通过结果，再显式调用：

- `POST /internal/v1/questions/productions/{requirement_id}/save`：提交 `project_id/requirement_id/scope_id/validation_id/draft_hash/requirements_hash/requirements_sources`。身份均为 UUID，哈希为完整 SHA256；`requirements_sources` 为客户端保存的字段来源对象（1～1000 项），例如 `{"topic":{"kind":"USER","message_id":"m1","text":"剪辑上传流程"},"profile":{"kind":"DEFAULT","value":"LANDSCAPE_720"}}`。请求最多 65536 UTF-8 字节。不接受替换草稿、条件或字幕；完整内容取自匹配的持久校验。
- `GET /internal/v1/questions/productions/{requirement_id}/save`：恢复已提交的原保存结果；尚未保存／需求不存在返回 404。返回阶段 1 `SavedResult`，无浏览器 data 外层包装。
- 既有 `GET /api/v1/revisions/{revision_id}`：按项目所有权读取正式版本。M2 版本增加 `productionSource`，含原草稿、完整条件、客户端条件来源、制作／scope／校验身份、哈希、规则版本、证据及读取调用引用、output_key 到 outputId 映射；旧任务来源版本不增加此字段。

所有输出在一个短数据库事务内保存为同一方案的 V1；分配永久 outputId/clipId，持久化素材／证据引用并同步提交保存结果。来源元数据与已验证条件分开保存，Java 不声称能证明原文被正确理解。V53 只增加版本上的条件来源 JSON 字段。

同需求行锁串行化校验发布和保存，并在保存时锁定、核对固定数据库依赖。草稿／条件哈希不符、回执不通过、其他需求的校验、已有不同内容的新校验或依赖失效都拒绝保存。数据库故障上抛并整体回滚，可以按原请求重试。相同保存请求重放、并发保存及响应丢失后的查询返回同一版本；已提交结果不因后来依赖失效被隐藏。保存后禁止新增草稿校验，M2 不派生编辑已有版本。

保存不产生确认、预览或导出。受控 HTTP 夹具验证新版本能被既有预览请求组装逻辑消费；Python 真实 DRAFT、修正循环及 CLI 保存仍属于阶段 4。验证记录见 [M2 阶段 3](../docs/verification/java-tools-m2-stage3.md)。
