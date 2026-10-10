# T20：一次生成多条短视频成片定义

状态：已发布为 [#21](https://github.com/Hou1464851806/AgenticVideoProject/issues/21)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户指定数量、主题和各条时长，从长视频得到同一方案版本内的多条短视频定义。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 方案结果卡能逐条选择成片定义，显示每条片段、标题／依据、字幕和预计时长，点击可预览源片段。
- [ ] 数量及各输出硬约束通过确定性校验，观点与必要上下文使用人工样本核验；素材不足时解释而不凑数或虚构。
- [ ] 同一生成操作提交一个包含全部输出的完整版本，各输出身份稳定；内部迭代不提交中间版本。
- [ ] 重新打开项目仍能查看全部输出与固定来源，查询、查看或切换输出不追加版本。

## Verification

真实 Agent 生成多条人工可核查短视频方案，包含不可满足数量／时长的反例。

## Blocked by

- [#20](https://github.com/Hou1464851806/AgenticVideoProject/issues/20)：从讲话视频生成一条可核查的短视频方案

## Requirement coverage

总需求用户故事：US-51, US-53, US-54。

总需求验收：AC-17, AC-22（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
