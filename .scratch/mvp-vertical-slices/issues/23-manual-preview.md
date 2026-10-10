# T23：手动生成带原声与字幕的低清效果预览

状态：已发布为 [#24](https://github.com/Hou1464851806/AgenticVideoProject/issues/24)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P0；标签为 `priority:P0` 和 `ready-for-agent`。

## What to build

用户主动请求某版本／成片定义的低清预览，查看横竖屏、原声、中文字幕与片段顺序的实际效果。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 仅明确手动请求触发合成，无需正式确认；源片段播放与合成预览明确区分，任务固定版本与输出。
- [ ] Worker 将方案编译成受控 FFmpeg 参数；真实验证中文字体、字幕映射、原声、硬切、16:9／9:16 等比例留边及无音轨策略。
- [ ] 相同版本、输出、实际规格、渲染规则及必要依赖指纹复用有效任务／产物，预览不再次调用 ASR；完成前验证可播放性、时长与音轨。
- [ ] 执行尝试使用独立工作区并检查空间，取消确认 FFmpeg 退出；结果／任务幂等发布，切换查看和追加版本不影响当前预览。

## Verification

短样本实际导出并播放，核验中文显示、音画／字幕时间和重复点击；重投、取消及上传产物后回报丢失验证。

## Blocked by

- [#20](https://github.com/Hou1464851806/AgenticVideoProject/issues/20)：从讲话视频生成一条可核查的短视频方案

## Requirement coverage

总需求用户故事：US-66, US-67, US-68, US-70, US-71, US-76, US-84, US-86。

总需求验收：AC-10, AC-23, AC-27, AC-29, AC-30（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
