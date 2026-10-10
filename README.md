# Agent 视频工作区

使用 Vue 3 和 Spring Boot 提供同源浏览器入口。业务数据保存在 PostgreSQL；独立 Java 媒体 Worker 通过 RabbitMQ 领取任务，使用 FFmpeg 验证视频与提取音频；独立 Python Agent 服务调用百炼异步语音识别。服务划分遵循 [架构设计](docs/architecture.md)。

## 本机启动

在 Apple Silicon Mac 上启动 Docker Desktop，然后执行：

```bash
./scripts/start.sh
```

脚本首次生成权限为 600 的 `.env`，其中数据库、存储、消息队列密码和内部执行凭证随机生成。打开 `http://localhost:8080` 创建账号、登录和退出。登录后可创建私有项目并打开素材、对话、播放器与结果工作区；刷新保留当前项目。在素材库选择多个视频，浏览器直接分片上传到 Silo；传输完成后显示待媒体验证状态。验证通过后可查看媒体信息并定位播放；验证失败显示原因和对象清理状态。修改端口时，同时修改 `.env` 中 `APP_PORT` 和 `APP_ALLOWED_ORIGIN`，后者须与浏览器使用的完整 Origin 一致。

PostgreSQL 使用 `postgres_data` 持久卷。`docker compose restart` 保留账号；业务服务重启后需要重新登录。数据库没有发布到宿主端口。Silo 的 S3 入口仅绑定本机 9000 端口，原始对象保存在 `silo_data` 持久卷；存储密码同样由启动脚本随机生成。

上传可暂停或继续，网络失败只有限重试当前分片。刷新后在原上传条目重选原文件，增量 SHA-256 校验通过后只补传存储中缺失的分片；同名同大小的不同文件不能续接。同一上传被另一页面控制时显示冲突原因，可回到原页面继续或等待写入资格到期。

## 语音证据

在 `.env` 配置北京区域的 `DASHSCOPE_API_KEY`，重新启动后，新上传的有音轨视频验证通过即自动分析。播放器显示媒体、语音和索引阶段，语音就绪后可点击句段核查原素材时间；字词同时保留音频时间与原素材时间。无音轨明确显示不适用，缺少凭据显示未验证及原因。原文提要用于导航。

默认原生异步模型为 `qwen3-asr-flash-filetrans`，北京接口为 `https://dashscope.aliyuncs.com/api/v1`；`DASHSCOPE_BASE_URL` 可配置完整原生 API 前缀。派生音频经官方私有临时上传传给百炼，服务保存提供方任务 ID 与用量；查询失败或执行器重启后查询原任务，提交回执丢失时标记结果不明确并停止自动重新提交。

素材库提供“查找原句”：输入人名、数字或原句，查看语音证据卡、邻近原文并定位原视频。当前仅有字面查询；未命中会列出语音分析覆盖，尚未具备语义检索能力。

## 开发验证

安装 JDK 17、Maven 3.9.9 和 Node.js 24，保持 Docker Desktop 运行：

```bash
mvn -B -ntp verify
cd frontend
npm ci
npm run build
npm run test:e2e
```

Compose 镜像构建完成并配置模型凭据后，在仓库根目录运行 `node scripts/test-models.mjs`，从固定视频提取测试音频并验证真实百炼句／字词响应。测试会调用付费接口，并保留测试任务标识供重复查询。完整浏览器套件也包含真实模型与网络故障验证。

Java 集成测试启动独立的真实 PostgreSQL、Silo 和 RabbitMQ 容器。浏览器测试使用已安装的 Google Chrome，并要求 Compose 服务先启动；它验证登录持久性、两账号项目隔离、窄屏入口、多文件直传、响应丢失恢复、真实媒体校验及 Range 播放。恢复测试会强制中断媒体 Worker，再验证任务恢复、临时目录清理、重复通知和进程取消。如果测试进程的 PATH 中没有 Docker，可通过 `DOCKER_BIN=/usr/local/bin/docker npm run test:e2e` 指定 CLI。

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
| 媒体 Worker 的 FFmpeg / ffprobe | 6.1.1-3ubuntu5，固定 apt 包版本 |
| Node 构建镜像 | Node 24 Bookworm Slim，固定 manifest digest |
| Vue / Vite / TypeScript | 3.5.43 / 8.3.4 / 5.9.3 |

镜像固定值见 Dockerfile 与 Compose；前端完整依赖固定在 `package-lock.json`。Spring Boot 的 [系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)支持 Java 17；PostgreSQL [官方镜像](https://hub.docker.com/_/postgres)提供 arm64 发行。镜像 manifest 和本机实际运行仍按每个切片验证。

2026-10-10 实测宿主为 arm64、16 GiB 内存，Docker Linux VM 分配 8,217,341,952 字节（约 7.65 GiB），实施前 Data 卷可用约 13 GiB。当前 Compose 限制 PostgreSQL、Silo、RabbitMQ、媒体 Worker 分别为 512 MiB / 1 CPU，业务入口为 768 MiB / 1.5 CPU；后续组件加入时需重新核对总预算。宿主空闲空间与 Docker 卷余量分别测量，不能把这个初始快照当作后续上传容量保证。
