# T16：停止对话任务并关联原需求重试失败

状态：已发布为 [#17](https://github.com/Hou1464851806/AgenticVideoProject/issues/17)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户明确停止不需要的对话计算，失败后重试原需求，历史不出现重复用户消息。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 会话提供显式停止，停止撤销结果发布资格并释放当前执行占用；已成功提交结果不能被迟到取消改为失败或取消。
- [ ] 用户重试终态失败创建关联任务，保留原失败记录并重新取得会话活动资格；自动重试沿用任务并新增尝试。
- [ ] 重试前核对权限、固定对象和已提交结果，已有成功提交直接返回，不重复消息或再次发布方案。
- [ ] 外部异步执行状态不明时先查询已保存身份，有限退避；外部服务能否停止及未知费用明确记录，不承诺完全撤销收费。

## Verification

停止／完成竞争、结果提交后通知丢失、失败重试及旧尝试迟到；以后新增编辑／渲染任务在各自 Issue 验证锁和进程的释放。

## Blocked by

- [#15](https://github.com/Hou1464851806/AgenticVideoProject/issues/15)：在独立制作会话中对话查询素材

## Requirement coverage

总需求用户故事：US-47, US-48, US-85, US-86。

总需求验收：AC-14, AC-21, AC-29, AC-30, AC-31（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
