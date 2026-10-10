# 实施状态

本轮按 ready-for-agent 的 32 张子任务实施；#1 为汇总需求。实施起点为 `63efde7`，在当前 main 分支逐票提交。

已确认测试边界：公开 HTTP 接口为主，关键 PostgreSQL／对象存储／消息／媒体／模型边界使用真实集成；浏览器验证用户操作闭环。

| Issue | 优先级 | 直接前置 | 状态 |
| --- | --- | --- | --- |
| [#2](https://github.com/Hou1464851806/AgenticVideoProject/issues/2) [T01] 本机启动后注册、登录与退出 | P0 | 无 | 实现与验证完成，见 [记录](verification/issue-2.md) |
| [#3](https://github.com/Hou1464851806/AgenticVideoProject/issues/3) [T02] 创建与打开自己的私有项目 | P0 | #2 | 实现与验证完成，见 [记录](verification/issue-3.md) |
| [#4](https://github.com/Hou1464851806/AgenticVideoProject/issues/4) [T03] 分片上传视频并看到待验证状态 | P0 | #3 | 实现与验证完成，见 [记录](verification/issue-4.md) |
| [#5](https://github.com/Hou1464851806/AgenticVideoProject/issues/5) [T04] 验证视频后在素材库定位播放 | P0 | #4 | 实现与验证完成，见 [记录](verification/issue-5.md) |
| [#6](https://github.com/Hou1464851806/AgenticVideoProject/issues/6) [T05] 断网、暂停与刷新后继续原上传 | P0 | #4 | 待实施 |
| [#7](https://github.com/Hou1464851806/AgenticVideoProject/issues/7) [T06] 取消或过期上传并查看残留清理 | P1 | #4 | 待实施 |
| [#8](https://github.com/Hou1464851806/AgenticVideoProject/issues/8) [T07] 超限或空间不足时阻止上传并解释原因 | P1 | #4 | 待实施 |
| [#9](https://github.com/Hou1464851806/AgenticVideoProject/issues/9) [T08] 上传后自动生成可定位的语音证据 | P0 | #5 | 待实施 |
| [#10](https://github.com/Hou1464851806/AgenticVideoProject/issues/10) [T09] 查找人名、数字和原句并跳转核查 | P0 | #9 | 待实施 |
| [#11](https://github.com/Hou1464851806/AgenticVideoProject/issues/11) [T10] 用主题和近义表达检索视频内容 | P1 | #10 | 待实施 |
| [#12](https://github.com/Hou1464851806/AgenticVideoProject/issues/12) [T11] 查询画面与可见文字并核查观察时刻 | P1 | #11 | 待实施 |
| [#13](https://github.com/Hou1464851806/AgenticVideoProject/issues/13) [T12] 在预算内复查候选，超预算先询问 | P1 | #12 | 待实施 |
| [#14](https://github.com/Hou1464851806/AgenticVideoProject/issues/14) [T13] 只恢复失败分析阶段并重建检索索引 | P1 | #12 | 待实施 |
| [#15](https://github.com/Hou1464851806/AgenticVideoProject/issues/15) [T14] 在独立制作会话中对话查询素材 | P0 | #10 | 待实施 |
| [#16](https://github.com/Hou1464851806/AgenticVideoProject/issues/16) [T15] 刷新或断线后恢复对话进度与结果 | P1 | #15 | 待实施 |
| [#17](https://github.com/Hou1464851806/AgenticVideoProject/issues/17) [T16] 停止对话任务并关联原需求重试失败 | P1 | #15 | 待实施 |
| [#18](https://github.com/Hou1464851806/AgenticVideoProject/issues/18) [T17] 纠正制作需求简报并回答关联澄清 | P1 | #15 | 待实施 |
| [#19](https://github.com/Hou1464851806/AgenticVideoProject/issues/19) [T18] 长对话保留硬条件并分页查看完整历史 | P1 | #18 | 待实施 |
| [#20](https://github.com/Hou1464851806/AgenticVideoProject/issues/20) [T19] 从讲话视频生成一条可核查的短视频方案 | P0 | #15 | 待实施 |
| [#21](https://github.com/Hou1464851806/AgenticVideoProject/issues/21) [T20] 一次生成多条短视频成片定义 | P1 | #20 | 待实施 |
| [#22](https://github.com/Hou1464851806/AgenticVideoProject/issues/22) [T21] 手动调整片段并从任意历史版本继续 | P1 | #20 | 待实施 |
| [#23](https://github.com/Hou1464851806/AgenticVideoProject/issues/23) [T22] 对话替换指定片段并保留其他内容 | P1 | #18, #20 | 待实施 |
| [#24](https://github.com/Hou1464851806/AgenticVideoProject/issues/24) [T23] 手动生成带原声与字幕的低清效果预览 | P0 | #20 | 待实施 |
| [#25](https://github.com/Hou1464851806/AgenticVideoProject/issues/25) [T24] 确认具体版本后明确导出并下载一条成片 | P0 | #24 | 待实施 |
| [#26](https://github.com/Hou1464851806/AgenticVideoProject/issues/26) [T25] 批量导出选定成片并展示部分完成 | P1 | #21, #25 | 待实施 |
| [#27](https://github.com/Hou1464851806/AgenticVideoProject/issues/27) [T26] 按需求跨素材编排并导出基础混剪 | P1 | #25 | 待实施 |
| [#28](https://github.com/Hou1464851806/AgenticVideoProject/issues/28) [T27] 隐藏、恢复和安全删除素材 | P1 | #20 | 待实施 |
| [#29](https://github.com/Hou1464851806/AgenticVideoProject/issues/29) [T28] 清理过期预览并按需重新生成 | P1 | #24 | 待实施 |
| [#30](https://github.com/Hou1464851806/AgenticVideoProject/issues/30) [T29] 回收派生媒体后仍能核查历史证据 | P1 | #12, #20 | 待实施 |
| [#31](https://github.com/Hou1464851806/AgenticVideoProject/issues/31) [T30] 查看分类存储、模型用量和费用预警 | P1 | #15 | 待实施 |
| [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) [T31] 在故障与权限边界下验收三条用户主线 | P2 | #6, #7, #8, #13, #14, #16, #17, #19, #22, #23, #26, #27, #28, #29, #30, #31 | 待实施 |
| [#33](https://github.com/Hou1464851806/AgenticVideoProject/issues/33) [T32] 从说明启动、测量并备份恢复完整系统 | P2 | #32 | 待实施 |

单票完成以对应实现、必要真实验证和独立 Git 提交为依据；ready-for-agent 标签本身不代表前置完成。所有票完成后，代码质量与需求覆盖分别审查，修复后重跑全部验证。
