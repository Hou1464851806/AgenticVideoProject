# T04：验证视频后在素材库定位播放

状态：已发布为 [#5](https://github.com/Hou1464851806/AgenticVideoProject/issues/5)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P0；标签为 `priority:P0` 和 `ready-for-agent`。

## What to build

上传视频经独立媒体 Worker 验证后出现在可用素材库，用户查看媒体信息并按时间播放。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 固定并真实运行独立 arm64 Java 媒体 Worker、ffprobe／FFmpeg；核对真实字节数、服务端 SHA-256、可解码性和时长后才发布素材。
- [ ] 损坏、哈希不符、超时长和不可解码视频显示原因且不进入可选素材；验证失败对象进入可核对的清理流程。
- [ ] 播放器支持经过项目鉴权的 Range 读取及时间定位；保存音画起点与原素材毫秒时间映射，覆盖可变帧率和无音轨素材。
- [ ] 实现任务原子领取后 ACK、当前尝试心跳／租约及幂等发布；重投通知不启动同尝试的第二个进程，迟到结果不能覆盖终态，取消时确认本地进程退出。

## Verification

真实媒体样本与 RabbitMQ／PostgreSQL 集成验证，覆盖有效／损坏视频、偏移／可变帧率、重投通知及领取后中断恢复。

## Blocked by

- [#4](https://github.com/Hou1464851806/AgenticVideoProject/issues/4)：分片上传视频并看到待验证状态

## Requirement coverage

总需求用户故事：US-15, US-16, US-17, US-18, US-85, US-86。

总需求验收：AC-02, AC-06, AC-10, AC-29, AC-30（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
