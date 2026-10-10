# T32：从说明启动、测量并备份恢复完整系统

状态：已发布为 [#33](https://github.com/Hou1464851806/AgenticVideoProject/issues/33)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P2；标签为 `priority:P2` 和 `ready-for-agent`。

## What to build

部署者按文档从本机启动完整系统，复现三条演示，查看真实性能／费用记录并完成备份恢复。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 固定最终依赖／镜像、模型配置、分片／采样／检索参数、字体、超时和限额，记录实际 arm64 部署与资源要求，不沿用过期磁盘快照。
- [ ] 代表样本记录分析、检索、方案生成、预览、导出耗时及 CPU／内存／磁盘峰值、每小时分析调用与费用；不宣称未验证 SLA。
- [ ] 说明启动、配置、升级／迁移与三条演示路径，敏感配置使用示例占位；其他本机环境按说明可复现。
- [ ] 演练 PostgreSQL 与对象备份协调恢复，核对项目、聊天、版本、引用和成片；说明 Qdrant 重建、checkpoint 恢复范围与外部任务不确定状态。

## Verification

在独立恢复环境从说明实际启动并恢复备份，重放三条用户演示，保存资源与费用测量记录。

## Blocked by

- [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32)：在故障与权限边界下验收三条用户主线

## Requirement coverage

总需求用户故事：US-87, US-88。

总需求验收：AC-33（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
