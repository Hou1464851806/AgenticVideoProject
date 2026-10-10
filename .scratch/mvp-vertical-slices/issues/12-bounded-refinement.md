# T12：在预算内复查候选，超预算先询问

状态：已发布为 [#13](https://github.com/Hou1464851806/AgenticVideoProject/issues/13)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户查询不确定或短暂画面内容时，系统在预算内补查；需要更多投入时先说明范围与费用并提供选择。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 针对候选区间加密采样或扩展邻近语句；没有候选时允许预算内覆盖性采样／换词，补查结果保留来源和时间。
- [ ] 调用次数、步骤、时间、帧数及输入量受程序预算限制；无新证据或预算耗尽停止，模型不能重置预算。
- [ ] 超过已有预算先展示拟扩大的素材／范围及开销说明，用户明确选择后才继续；拒绝后保留已获得结果。
- [ ] 细查生成可溯源的新证据，不覆盖历史观察；结果仍说明覆盖边界，不声称找全所有短暂文字。

## Verification

标注短暂画面、小字和无候选负例，核对预算内补查、拒绝／接受扩大预算与有限退出；独立 HTTP／结果卡可演示，不依赖制作会话。

## Blocked by

- [#12](https://github.com/Hou1464851806/AgenticVideoProject/issues/12)：查询画面与可见文字并核查观察时刻

## Requirement coverage

总需求用户故事：US-31, US-32, US-33, US-34。

总需求验收：AC-11, AC-31（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
