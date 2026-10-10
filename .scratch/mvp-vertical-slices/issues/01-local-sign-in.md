# T01：本机启动后注册、登录与退出

状态：已发布为 [#2](https://github.com/Hou1464851806/AgenticVideoProject/issues/2)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P0；标签为 `priority:P0` 和 `ready-for-agent`。

## What to build

部署者通过 Compose 启动系统，用户在浏览器注册、登录、退出，重启后账号仍可使用。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 固定当前切片所需的依赖版本及 arm64 镜像，实测本机内存与磁盘，记录资源限额；后续执行服务沿用已确认的独立进程边界。
- [ ] 注册、登录和退出可以从浏览器完成；重名注册、错误密码和未登录访问返回明确结果。
- [ ] 同源入口、数据库迁移和持久卷有效；重启服务不丢账号，退出后原登录资格失效。
- [ ] 浏览器不接触内部服务凭据；修改请求具备匹配登录方式的 CSRF／Origin 防护，日志不记录密码或密钥。

## Verification

真实 Compose 与 PostgreSQL 上验证浏览器登录、退出失效及重启保留；不以容器健康检查代替用户操作。

## Blocked by

None (can start immediately)。

## Requirement coverage

总需求用户故事：US-01, US-87。

总需求验收：AC-33（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
