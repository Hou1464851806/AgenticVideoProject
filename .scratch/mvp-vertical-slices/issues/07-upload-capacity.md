# T07：超限或空间不足时阻止上传并解释原因

状态：已发布为 [#8](https://github.com/Hou1464851806/AgenticVideoProject/issues/8)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户在超额或本机空间不足时收到具体提示，多个同时上传的请求不能绕过限额。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 默认单文件 2 GiB、每项目 10 个视频及原始素材 5 GiB，均可配置；时长 60 分钟由媒体验证阶段按真实媒体执行。
- [ ] 服务端在创建、分片签发和完成核对中应用声明大小、分片规则及实际字节；多个并发请求的空间和名额预留不可超卖。
- [ ] 实测 Docker／对象卷可用空间，预留覆盖未完成上传、原始文件、派生媒体、工作区与输出；探测和渲染在执行前复查空间。
- [ ] 浏览器明确显示被哪个限制拒绝及可行操作；对 Silo 不能强制的预签名长度条件记录残余窗口与收敛策略，不把客户端声明当物理保证。

## Verification

小限额配置下验证临界大小、并发预留、低空间与真实分片超额；后续执行功能复用并验证执行前检查。

## Blocked by

- [#4](https://github.com/Hou1464851806/AgenticVideoProject/issues/4)：分片上传视频并看到待验证状态

## Requirement coverage

总需求用户故事：US-14。

总需求验收：AC-06（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
