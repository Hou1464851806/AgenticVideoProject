# T11：查询画面与可见文字并核查观察时刻

状态：已发布为 [#12](https://github.com/Hou1464851806/AgenticVideoProject/issues/12)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户查找实际画面场景或屏幕文字，获得视觉证据并在播放器核查对应观察时刻。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 真实验证代表帧传入百炼 VLM，记录实际帧时间、模型版本及稀疏采样覆盖，处理重复画面在不同时刻出现。
- [ ] 画面观察、可见文字及语音提及分开保存和展示，不把语音提到物体当作画面出现。
- [ ] 视觉证据进入受控混合检索；单帧时间不表述为未经观察的持续区间，结果可访问佐证帧并跳转原视频。
- [ ] 视觉分析与索引状态独立，失败不阻塞语音；未命中解释视觉覆盖及可能遗漏，不承诺逐帧完整识别。

## Verification

真实 VLM 与人工标注画面／屏幕文字样本，覆盖只有语音提及、只有画面出现、重复帧与可变帧率。

## Blocked by

- [#11](https://github.com/Hou1464851806/AgenticVideoProject/issues/11)：用主题和近义表达检索视频内容

## Requirement coverage

总需求用户故事：US-22, US-24, US-27, US-28, US-29, US-30, US-34。

总需求验收：AC-07, AC-09, AC-10, AC-11（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。

外部条件：实施者需具备对应提供方凭据与人工可核对素材。凭据缺失可完成独立编码／模拟验证，但必须保留真实联调未通过的状态。
