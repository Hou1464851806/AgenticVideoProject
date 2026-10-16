# 叙帧 · 视频工作台

对话生命周期已接通浏览器 → Java → RabbitMQ → Python Worker：自然语言问答／讨论、生成及修改草稿、确认前低清预览、整体确认、方案列表正式导出与播放下载。阶段验收见[第 7 步记录](docs/verification/conversation-lifecycle-step7.md)，体验顺序见[功能说明](docs/current-features.md)。

此前 M4 步骤 1～5 已完成：内部预览与 CLI、真实视频／故障验收、用户完整观看试听、阶段两轴审查、Java/Python 完整非付费回归及后端打包均已通过。范围、证据与验证限制见 [M4 实施记录](docs/verification/java-tools-m4.md)。


使用 Vue 3 和 Spring Boot 提供同源浏览器入口。Java 后端已收敛为 `backend/` 下的单个 Maven 应用，通过 RabbitMQ 在同一进程中执行媒体探测、音频准备、稀疏抽帧和既有预览/导出。PostgreSQL 保存权威业务状态，Silo 保存对象，Qdrant 和 RabbitMQ 保留。M1 已贯通真实上传、Java 百炼分析与索引、Python CLI 四工具问答；综合验收、故障与媒体回归及未验证边界见 [第五阶段记录](docs/verification/java-tools-m1-stage5.md)，运行方式见 [后端说明](backend/README.md)。

## 本机启动

在 Apple Silicon Mac 上启动 Docker Desktop，然后执行：

```bash
./scripts/start.sh
```

脚本同时构建业务服务及单实例 Python Worker。首次生成配置后，在根 `.env` 补齐 `DASHSCOPE_API_KEY`（素材分析）和 `DEEPSEEK_API_KEY`（Agent），再次运行脚本应用。Worker 的 `agent_state` 卷保存执行 checkpoint 和付费回执，请保留该卷。

脚本首次生成权限为 600 的 `.env`，其中数据库、存储、消息队列密码和内部执行凭证随机生成。打开 `http://localhost:8080` 创建账号、登录和退出。登录后可创建私有项目并打开素材、对话、播放器与结果工作台；刷新保留当前项目。在素材库选择多个视频，浏览器直接分片上传到 Silo；传输完成后显示待媒体验证状态。验证通过后可查看媒体信息并定位播放；验证失败显示原因和对象清理状态。修改端口时，同时修改 `.env` 中 `APP_PORT` 和 `APP_ALLOWED_ORIGIN`，后者须与浏览器使用的完整 Origin 一致。

PostgreSQL 使用 `postgres_data` 持久卷。`docker compose restart` 保留账号；业务服务重启后需要重新登录。数据库没有发布到宿主端口。Silo 的 S3 入口仅绑定本机 9000 端口，原始对象保存在 `silo_data` 持久卷；存储密码同样由启动脚本随机生成。

Silo WebUI 固定为 [http://localhost:9001](http://localhost:9001)，同样仅绑定本机。使用用户名 `agentic-storage` 和项目 `.env` 中的 `STORAGE_SECRET_KEY` 登录；9000 是 S3 API 端口。修改 Silo 的启动参数或端口映射后，执行 `docker compose up -d --no-deps silo` 重建该容器并应用配置，数据卷保留；`docker compose restart silo` 不会应用这些配置变更。

上传可暂停或继续，网络失败只有限重试当前分片。刷新后在原上传条目重选原文件，增量 SHA-256 校验通过后只补传存储中缺失的分片；同名同大小的不同文件不能续接。同一上传被另一页面控制时显示冲突原因，可回到原页面继续或等待写入资格到期。

上传也可取消，取消后停止后续分片并释放素材名额，真实存储残留清理完成后才释放字节占用，页面分别显示两种状态。未完成上传默认在最后合法活动后 24 小时过期，界面显示恢复期限；过期后需重新上传。可通过 `.env` 的 `UPLOAD_INACTIVITY_SECONDS` 配置期限；已发布素材不受上传取消或过期清理影响。

## 分析与 Agent 当前状态

M1 已实现并运行综合验收。当前上传执行真实媒体准备，Compose 默认开启 Java 基础分析：有音轨进行全音轨 ASR，按稀疏帧执行 VLM，并生成文档 Embedding / Qdrant 索引。需配置 `DASHSCOPE_API_KEY`；缺凭据时对应阶段明确失败，已完成的其他来源仍可用。无音轨 ASR 标为不适用；稀疏视觉不代表逐帧覆盖。权威证据和付费回执由 PostgreSQL 保存。

旧浏览器 Agent 协议继续停用；新版 `/turns` 接通问答／讨论、草稿／恢复、聊天内预览和整体确认，见[完整设计](docs/conversation-lifecycle-browser-design.md)及[第 7 步验收](docs/verification/conversation-lifecycle-step7.md)。独立 Python CLI 的离线 Mock 模式继续可用，见 [Python 说明](agent-service/README.md)；Java 问答快照与四个读取工具已实现，Python 真实模式已接入，问答／补答／恢复使用固定快照，见 [工具阶段记录](docs/verification/java-tools-m1-stage3.md)与 [Python 接入记录](docs/verification/java-tools-m1-stage4.md)。M2 已接通真实草稿、Java 校验及显式 CLI 保存，受控闭环见 [M2 阶段 4 记录](docs/verification/java-tools-m2-stage4.md)，真实模型制作、多轮修改、保存及失败样本见 [M2 阶段 5 记录](docs/verification/java-tools-m2-stage5.md)。

## 既有方案与媒体渲染

已有方案版本仍可从工作台查看、播放源片段、生成低清预览、确认并导出。低清预览无需正式确认；正式导出必须先确认具体版本，再选择 720p／1080p。预览和导出固定请求时的方案版本、素材及输出规格，产物可播放和下载。正式成片也可显式删除，下载本身不删除文件。

浏览器直接输入制作或修改要求；当前已校验草稿下可预览或确认全部输出，确认后形成不可变 V1 并出现在方案列表。正式导出仍需点击按钮，多输出每次选择一条。确认后可继续聊天或另起制作；已冻结版本派生编辑尚未开放。自然语言预览／确认／导出会说明页面按钮，不直接执行媒体操作。Python CLI 保留独立开发与验收用途。

## 开发验证

安装 JDK 17、Maven 3.9.9、Node.js 24，以及用于宿主媒体测试的 FFmpeg/ffprobe，保持 Docker Desktop 运行：

```bash
mvn -B -ntp verify
cd frontend
npm ci
npm run build
```

Java 集成测试启动独立 PostgreSQL、Silo 和 RabbitMQ 容器，新 `LocalMediaIntegrationTest` 同时运行宿主 FFmpeg，验证真实上传、音轨偏移、稀疏帧、预览/导出、重投、有限重试、死信、取消与 broker 故障。旧协议测试通过显式 legacy 测试配置保留业务规则回归，不代表旧 Worker 运行形态仍受支持。

完整历史浏览器测试仍包含已退役的执行链路，不能代替 M1 验收。`node scripts/test-models.mjs` 已改为 Java 第二阶段付费验收，上传四个固定视频并验证真实模型证据及可查询索引；缺凭据或测试跳过返回退出码2。该入口同时验证四工具 HTTP 读取与回执复用；Python Agent 问答、真实模式与快照恢复见 [第四阶段记录](docs/verification/java-tools-m1-stage4.md)；综合验收见 [第五阶段记录](docs/verification/java-tools-m1-stage5.md)。

综合验收入口（会产生真实模型费用）：

```sh
agent-service/.venv/bin/python agent-service/scripts/verify_java_m1.py --output agent-service/.local/stage5-new
```

需配置 `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY` 和内部凭据；缺凭据明确返回未验证，详细操作见 [Python README](agent-service/README.md)。

## 当前固定版本与资源

| 依赖 | 版本 |
| --- | --- |
| Spring Boot | 3.5.16 |
| Java 字节码 | 17 |
| 构建镜像 | Maven 3.9.9 / Temurin 17，固定 manifest digest |
| 运行镜像 | Temurin 17.0.20.1_1 JRE Noble，固定 manifest digest |
| Silo | RELEASE.2026-09-16T00-00-00Z，固定 manifest digest |
| AWS S3 Java SDK / hash-wasm | 2.55.14 / 4.12.0 |
| PostgreSQL | 18.6 Bookworm，固定 manifest digest |
| RabbitMQ | 4.3.6-management，固定 manifest digest |
| 后端 FFmpeg / ffprobe | 6.1.1-3ubuntu5，固定 apt 包版本 |
| Node 构建镜像 | Node 24 Bookworm Slim，固定 manifest digest |
| Vue / Vite / TypeScript | 3.5.43 / 8.3.4 / 5.9.3 |

镜像固定值见 Dockerfile 与 Compose；前端完整依赖固定在 `package-lock.json`。Spring Boot 的 [系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)支持 Java 17；PostgreSQL [官方镜像](https://hub.docker.com/_/postgres)提供 arm64 发行。镜像 manifest 和本机实际运行仍按每个切片验证。

2026-10-10 实测宿主为 arm64、16 GiB 内存，Docker Linux VM 分配 8,217,341,952 字节（约 7.65 GiB），实施前 Data 卷可用约 13 GiB。当前 Compose 限制 PostgreSQL、Silo、RabbitMQ、Qdrant 分别为 512 MiB / 1 CPU，合并后的 Java 后端为 1280 MiB / 2 CPU；后续实测时需重新核对总预算。宿主空闲空间与 Docker 卷余量分别测量，不能把这个初始快照当作后续上传容量保证。

当前已实现功能和推荐体验顺序见 [功能说明](docs/current-features.md)。

### 模型调用记录大小配置

`MODEL_RECORD_REQUEST_MAX_BYTES` 默认 16777216（16 MiB），`MODEL_RECORD_RESPONSE_MAX_BYTES` 默认 4194304（4 MiB）。二者控制内部登记 JSON 字段的 UTF-8 序列化字节数，均须为正数；超限会明确反馈而不反复提交。修改运行配置后重建 business-api 容器生效。这不是模型 Token 窗口设置；调用记录仍完整保存在 PostgreSQL。修复范围和验证见 [记录](docs/verification/model-record-limits.md)。

### 上传与空间配置

单文件、项目原始素材总量及视频数量分别由 `UPLOAD_MAX_FILE_BYTES`（默认 2 GiB）、`UPLOAD_MAX_PROJECT_BYTES`（默认 5 GiB）、`UPLOAD_MAX_PROJECT_ASSETS`（默认 10）控制。`CAPACITY_MIN_FREE_BYTES` 默认保留 512 MiB 安全余量；`CAPACITY_MAX_RESERVED_BYTES` 可进一步限制全部项目的未来写入预算。Compose 实测挂载的对象及工作区卷，未完成上传和排队媒体任务先预留后续写入量。非 Compose 部署需把 `CAPACITY_PATHS` 配成实际对象和工作区文件系统路径。低空间时先清理或扩容再重试。

分片签名不能提供物理硬配额，实际分片超额会被拒绝、撤销并持续核对清理；详见 [#8 验证与残余窗口](docs/verification/issue-8.md)。
