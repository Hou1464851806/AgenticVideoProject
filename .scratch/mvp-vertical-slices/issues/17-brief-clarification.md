# T17：纠正制作需求简报并回答关联澄清

状态：已发布为 [#18](https://github.com/Hou1464851806/AgenticVideoProject/issues/18)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户用自然语言表达制作条件，检查并纠正需求简报；关键缺失或冲突时回答明确的问题继续原轮次。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 简报显示主题、数量、时长、素材范围与禁用内容，区分明确约束和已说明默认值；新消息可纠正并保留条件来源。
- [ ] 关键条件缺失／冲突发布有身份的问题卡；回答关联原问题与轮次，不能把任意新消息猜成回答。
- [ ] 等待输入持久保存运行状态，保留会话活动关系但释放执行槽及方案编辑占用；重启不盲目重跑或重复发布问题。
- [ ] 用户可回答、取消或明确开始新需求；恢复重验权限和对象，变更基准建立关联新任务，原等待不悄悄复活。

## Verification

浏览器纠正时长／禁用素材、回答问题、等待时重启和开始新需求；验证 checkpoint 重执行副作用幂等。

## Blocked by

- [#15](https://github.com/Hou1464851806/AgenticVideoProject/issues/15)：在独立制作会话中对话查询素材

## Requirement coverage

总需求用户故事：US-37, US-38, US-39, US-40, US-41, US-65。

总需求验收：AC-15, AC-16（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
