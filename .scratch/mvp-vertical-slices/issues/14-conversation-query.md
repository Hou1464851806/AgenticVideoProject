# T14：在独立制作会话中对话查询素材

状态：已发布为 [#15](https://github.com/Hou1464851806/AgenticVideoProject/issues/15)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P0；标签为 `priority:P0` 和 `ready-for-agent`。

## What to build

用户创建多个制作会话，通过自然语言查询已有语音证据，获得带出处的公开回答。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 同项目可创建多个制作会话，消息和上下文独立；固定素材范围及显式素材引用，查询不要求关联方案。
- [ ] 真实验证 DeepSeek 工具协议与 LangGraph 单 Agent 的受控查询；模型通过有权限的业务工具读取证据，不直接访问数据库、对象存储或 shell。
- [ ] 消息、轮次、任务及 Outbox 同事务接纳；相同幂等键和内容返回原结果，同会话忙碌时拒绝新需求但允许编辑输入草稿。
- [ ] 公开历史、最终回答及引用持久保存；回答有可核查出处，无证据诚实说明，工具循环有预算／退出条件，私有推理、路径和凭据不进入公开内容。

## Verification

真实 DeepSeek 与人工标注查询验证回答出处；重复 POST、多标签页并发及多会话独立上下文测试。

## Blocked by

- [#10](https://github.com/Hou1464851806/AgenticVideoProject/issues/10)：查找人名、数字和原句并跳转核查

## Requirement coverage

总需求用户故事：US-04, US-36, US-42, US-43, US-44, US-45。

总需求验收：AC-13, AC-16, AC-22, AC-31（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。

外部条件：实施者需具备对应提供方凭据与人工可核对素材。凭据缺失可完成独立编码／模拟验证，但必须保留真实联调未通过的状态。
