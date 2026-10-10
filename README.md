# Agent 视频工作区

使用 Vue 3 和 Spring Boot 提供同源浏览器入口。业务数据保存在 PostgreSQL；后续媒体 Worker 和 Python Agent 服务使用独立进程与资源限额，遵循 [架构设计](docs/architecture.md)。

## 本机启动

在 Apple Silicon Mac 上启动 Docker Desktop，然后执行：

```bash
./scripts/start.sh
```

脚本首次生成权限为 600 的 `.env`，其中数据库密码随机生成。打开 `http://localhost:8080` 创建账号、登录和退出。登录后可创建私有项目并打开素材、对话、播放器与结果工作区；刷新保留当前项目。在素材库选择多个视频，浏览器直接分片上传到 Silo；传输完成后显示待媒体验证状态。修改端口时，同时修改 `.env` 中 `APP_PORT` 和 `APP_ALLOWED_ORIGIN`，后者须与浏览器使用的完整 Origin 一致。

PostgreSQL 使用 `postgres_data` 持久卷。`docker compose restart` 保留账号；业务服务重启后需要重新登录。数据库没有发布到宿主端口。Silo 的 S3 入口仅绑定本机 9000 端口，原始对象保存在 `silo_data` 持久卷；存储密码同样由启动脚本随机生成。

## 开发验证

安装 JDK 17、Maven 3.9.9 和 Node.js 24，保持 Docker Desktop 运行：

```bash
mvn -B -ntp verify
cd frontend
npm ci
npm run build
npm run test:e2e
```

Java 集成测试启动独立的真实 PostgreSQL 18.6 容器。浏览器测试使用已安装的 Google Chrome，并要求 Compose 服务先启动；它通过浏览器创建账号，然后重建数据库和业务容器并验证登录、退出，同时验证两账号项目隔离、窄屏工作区入口、多文件真实直传和创建／完成响应丢失恢复。如果测试进程的 PATH 中没有 Docker，可通过 `DOCKER_BIN=/usr/local/bin/docker npm run test:e2e` 指定 CLI。

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
| Node 构建镜像 | Node 24 Bookworm Slim，固定 manifest digest |
| Vue / Vite / TypeScript | 3.5.43 / 8.3.4 / 5.9.3 |

镜像固定值见 Dockerfile 与 Compose；前端完整依赖固定在 `package-lock.json`。Spring Boot 的 [系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)支持 Java 17；PostgreSQL [官方镜像](https://hub.docker.com/_/postgres)提供 arm64 发行。镜像 manifest 和本机实际运行仍按每个切片验证。

2026-10-10 实测宿主为 arm64、16 GiB 内存，Docker Linux VM 分配 8,217,341,952 字节（约 7.65 GiB），实施前 Data 卷可用约 13 GiB。当前 Compose 限制 PostgreSQL 为 512 MiB / 1 CPU，业务入口为 768 MiB / 1.5 CPU；后续组件加入时需重新核对总预算。宿主空闲空间与 Docker 卷余量分别测量，不能把这个初始快照当作后续上传容量保证。
