# Issue #2：本机启动后注册、登录与退出

验证环境：2026-10-10，macOS arm64，Docker Desktop Linux aarch64 VM。本票代码从 `63efde7` 开始实现。

## 行为与验证边界

通过真实 HTTP 接口测试注册及大小写归一后的用户名唯一、错误密码、登录 Cookie、退出后原 Cookie 失效、CSRF 与 Origin 拒绝、UTF-8 密码长度边界、认证入口限流。测试使用真实 PostgreSQL 18.6，每次套件使用独立数据库容器。

浏览器测试使用真实 Google Chrome 和 Compose 同源入口。测试创建账号，重建 PostgreSQL 与业务容器后重新登录并退出，确认持久卷保留账号；同时检查注册密码未进入业务日志。登录页截图已人工查看。

## 固定依赖与资源

Compose PostgreSQL 18.6 和 Java 应用实际运行架构均为 arm64。运行 JRE 为 Temurin 17.0.20.1+1，Spring Boot 为 3.5.16；构建和基础镜像使用 manifest digest，前端依赖使用精确版本与 lockfile。

宿主内存 17,179,869,184 字节；Docker VM 内存 8,217,341,952 字节。启动前宿主 Data 卷约 13 GiB 可用。容器重建并完成登录测试后，Docker PostgreSQL 卷所在文件系统可用 46,119,944,192 字节；这个 VM 数值不代表宿主可继续增长同样大小。

空闲采样：业务入口 178.4 MiB / 768 MiB 限额，PostgreSQL 41.78 MiB / 512 MiB 限额。此记录是本票小样本的空闲占用，后续上传、分析与媒体执行仍需各自重新测量。Worker 与 Agent 尚未加入本票部署，沿用独立进程和资源限制边界。

## 结果记录

- HTTP 测试先在缺少接口时返回 401；跨 Origin 在未实现防护时错误返回 201；超长 UTF-8 密码和限流场景也先失败，再补实现。
- `mvn -B -ntp verify`：Java 完整构建和集成测试通过。
- `npm run build`：Vue TypeScript 检查与 Vite 生产构建通过。
- `docker compose up --build -d`：固定镜像上的 arm64 完整应用构建和启动通过。
- `npm run test:e2e`：浏览器创建账号、重建容器、登录和退出通过。

数据库密码由启动脚本随机生成到权限为 600 的未跟踪 `.env`，不作为前端构建参数。API 使用 HttpOnly / SameSite=Lax 会话 Cookie，写操作需要 CSRF token，并验证配置的 Origin；同源部署不向浏览器发布内部服务凭据。
