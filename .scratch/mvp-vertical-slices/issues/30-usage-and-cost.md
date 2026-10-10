# T30：查看分类存储、模型用量和费用预警

状态：已发布为 [#31](https://github.com/Hou1464851806/AgenticVideoProject/issues/31)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户在项目查看原始素材、派生媒体、预览、成片占用，以及各用途模型调用和估算费用。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 存储按四类展示实际已占用及未完成预留／待清理状态，没有产物的类别显示真实零值；计量不依赖前端猜测。
- [ ] 调用记录展示提供方、模型、用途、实际报告用量和估算费用，没有实际用量明确标估算；记录有限重试及状态不明调用。
- [ ] 配置费用／用量预警阈值并在达到时提示；不引入固定月预算或计费商业化流程。
- [ ] 已有分析结果可复用，查询可核对用途和费用；后续手动重排、预览、导出及缓存重建的切片验证不新增无必要模型调用。

## Verification

真实 ASR／DeepSeek 等已有调用与小阈值预警，核对模型记录和物理对象分类计量；后续功能沿用计量协议。

## Blocked by

- [#15](https://github.com/Hou1464851806/AgenticVideoProject/issues/15)：在独立制作会话中对话查询素材

## Requirement coverage

总需求用户故事：US-80, US-83, US-84。

总需求验收：AC-31, AC-32（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
