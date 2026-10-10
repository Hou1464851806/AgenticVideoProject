# T21：手动调整片段并从任意历史版本继续

状态：已发布为 [#22](https://github.com/Hou1464851806/AgenticVideoProject/issues/22)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户调整片段起止时间和顺序，查看旧版或基于旧版继续，完整保留历史与修改来源。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 手动调整合法片段时间／顺序后追加完整版本，未改输出和片段内容保持；字幕按新成片时间重映射，复用分析。
- [ ] 已有 V1、V2、V3 时基于 V1 修改生成 V4 且来源为 V1；单纯查看／切换不新增版本。
- [ ] Agent 占用同方案时手动及第二次修改被拒绝，仍能查看历史；不同会话也不能绕过同方案互斥。
- [ ] 运行输入与查看位置分开，完成提示新版本而不强制切换；非法范围拒绝，失败不留下半成品版本。

## Verification

浏览器完成 V1→V2→V3 后由 V1 生成 V4 的演示，验证越界、并发编辑及字幕时间重映射。

## Blocked by

- [#20](https://github.com/Hou1464851806/AgenticVideoProject/issues/20)：从讲话视频生成一条可核查的短视频方案

## Requirement coverage

总需求用户故事：US-57, US-58, US-59, US-60, US-61, US-62, US-63, US-64, US-84。

总需求验收：AC-19, AC-21, AC-32（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。
