# T06：取消或过期上传并查看残留清理

状态：已发布为 [#7](https://github.com/Hou1464851806/AgenticVideoProject/issues/7)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户取消上传并看到清理进展；过期上传停止续写，空间在实际清理后释放。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 取消与完成原子竞争，撤销后续写入资格并停止前端调度；已签发和在途请求结束后继续核对残留。
- [ ] 真实 Silo Abort 与重复清理可回收未完成分片、未入库对象；完成中的有效会话、已发布素材和其他项目对象不被误删。
- [ ] 最后合法活动后默认 24 小时过期，可配置；界面显示恢复期限，过期后提示重新上传。
- [ ] 名额与字节占用分别核对，未真正清理的物理残留仍计入容量，界面区分已取消与清理完成。

## Verification

注入取消／完成竞争、Abort 失败与在途 PUT，检查对象残留及占用；使用可调时钟／短配置验证过期。

## Blocked by

- [#4](https://github.com/Hou1464851806/AgenticVideoProject/issues/4)：分片上传视频并看到待验证状态

## Requirement coverage

总需求用户故事：US-13, US-14。

总需求验收：AC-05, AC-06（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
