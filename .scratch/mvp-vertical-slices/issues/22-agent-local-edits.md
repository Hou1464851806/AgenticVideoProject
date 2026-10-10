# T22：对话替换指定片段并保留其他内容

状态：已发布为 [#23](https://github.com/Hou1464851806/AgenticVideoProject/issues/23)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户显式引用版本、输出或片段，说“替换第二段”“更紧凑但保留结论”，得到有变更说明的新方案版本。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 发送时固定版本、输出／片段与素材范围，指代不明时澄清；执行中切换查看不改变修改基准。
- [ ] 只修改被要求的范围，新完整版本保留未要求改变的输出、片段及硬约束；变更说明与实际内容一致。
- [ ] 基于旧版修改记录真实来源并追加至历史末尾，Agent 草稿／自检与 checkpoint 恢复不重复生成版本。
- [ ] 跨会话同方案编辑互斥；等待澄清释放编辑权，恢复重验基准，取消／故障后可再编辑，新版本就绪不强制切换。

## Verification

真实 Agent 的定向替换和紧凑化样本，比较目标外内容不变；并发、旧版分支和通知丢失验证。

## Blocked by

- [#18](https://github.com/Hou1464851806/AgenticVideoProject/issues/18)：纠正制作需求简报并回答关联澄清
- [#20](https://github.com/Hou1464851806/AgenticVideoProject/issues/20)：从讲话视频生成一条可核查的短视频方案

## Requirement coverage

总需求用户故事：US-42, US-55, US-56, US-59, US-61, US-62, US-63, US-64, US-65。

总需求验收：AC-19, AC-20, AC-21, AC-22（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
