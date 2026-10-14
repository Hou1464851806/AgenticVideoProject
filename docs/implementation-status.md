# 实施状态

## 2026-10-14 Java 工具 M1 重构

已授权开始实施，第一阶段单应用、媒体执行、MQ 与已有预览/导出已落地；2026-10-15 继续第二阶段，Java 百炼 ASR/VLM/Embedding、分来源证据及 Qdrant 索引已接入。四个固定视频的真实付费模型验收通过，详见 [第二阶段记录](verification/java-tools-m1-stage2.md)。网络恢复后已补齐新版镜像构建、Compose 启动、现有数据库 V49 迁移和两项浏览器冒烟验证。真实基础设施检查及未完成范围见 [阶段记录](verification/java-tools-m1-stage1.md)，当前运行方式见 [后端 README](../backend/README.md)。2026-10-15 第三阶段问答范围快照、四读取工具、回执、稳定分页和通道降级已实现，验证见 [第三阶段记录](verification/java-tools-m1-stage3.md)；2026-10-15 第四阶段 Python JavaToolExecutor、真实模式与点证据引用已接入；真实 DeepSeek 问答、跨进程补答和恢复验证见 [第四阶段记录](verification/java-tools-m1-stage4.md)。2026-10-15 第五阶段运行真实新上传到四工具/DeepSeek问答、保留语义集、SIGKILL恢复、MQ故障和媒体回归；清理旧Python分析路径与Compose服务，具体结果和未验证边界见[第五阶段记录](verification/java-tools-m1-stage5.md)。最终提交前完成双轴收尾审查，修复多检索分组累计突破通道候选上限的问题；最新验证与未验证边界见[收尾审查](verification/java-tools-m1-closeout.md)。本文件以下条目是历史交付记录，不能替代重构后的 M1 验收。


本轮按 ready-for-agent 的 32 张子任务实施；#1 为汇总需求。实施起点为 `63efde7`，在当前 main 分支逐票提交。

11 张 P0 任务均已实现、验证并逐票本地提交。P1 #7、#8、#11、#12、#13、#14、#16、#17、#18、#31、#27 已完成。继续按优先级和依赖推进其他 P1 / P2。当前功能见 [功能清单](current-features.md)，实现原理与代码入口见 [项目讲解](project-walkthrough.md)。

已确认测试边界：公开 HTTP 接口为主，关键 PostgreSQL／对象存储／消息／媒体／模型边界使用真实集成；浏览器验证用户操作闭环。

## 2026-10-12 新增设计，待实施

[统一自然语言流程](natural-language-workflow-design.md)及 ADR-0015 已确认；本轮只落地文档。自动会话、意图理解后接纳、同输入框澄清、需求隔离、自动补查授权与累计额度、连续步骤和单条完整对话验收尚未实施。局部修改 #23 于 2026-10-13 实施；多条生成 #21 与批量导出 #26 留在后续里程碑，仍属于原 MVP 范围。

新增本地任务 NL1–NL5 见 [开发任务](development-backlog.md)。这些编号不是 GitHub Issue，尚未创建或修改外部任务；既有已完成票的状态不因新增设计变更。

## 2026-10-13 自然语言流程实施

已发布 #34–#42。本轮按依赖实施 #34–#38，逐票 TDD、类型检查和提交；原定五票完成后统一回归；按用户要求先完成 #35 后暂停，本次先完成 #36，执行完整回归及双轴审查；随后用户授权继续 #37、#38，仍逐票 TDD、类型检查和独立提交。

| Issue | 前置 | 状态 |
| --- | --- | --- |
| #34 新聊天与自然语言查找 | 无 | 实现与单票验证完成，见 [记录](verification/issue-34.md) |
| #35 一句话生成讲话方案并摘要 | #34 | 实现与单票验证完成，见 [记录](verification/issue-35.md) |
| #36 同输入框澄清、取消与新需求 | #35 | 实现完成，验证与审查见 [记录](verification/issue-36.md) |
| #37 自然语言跨素材混剪 | #36 | 实现、验证与双轴审查完成，见 [记录](verification/issue-37.md) |
| #38 聊天预览、确认与导出 | #36、#37 | 实现、验证与双轴审查完成，见 [记录](verification/issue-38.md) |

#34–#38 已按依赖完成并逐票本地提交。最终完整 Maven 回归 123/123；浏览器整轮 85/86，唯一旧界面初始化超时项在同一代码下独立复验通过。所有本轮新增浏览器验收在整轮通过。

## 原任务进度

| Issue | 优先级 | 直接前置 | 状态 |
| --- | --- | --- | --- |
| [#2](https://github.com/Hou1464851806/AgenticVideoProject/issues/2) [T01] 本机启动后注册、登录与退出 | P0 | 无 | 实现与验证完成，见 [记录](verification/issue-2.md) |
| [#3](https://github.com/Hou1464851806/AgenticVideoProject/issues/3) [T02] 创建与打开自己的私有项目 | P0 | #2 | 实现与验证完成，见 [记录](verification/issue-3.md) |
| [#4](https://github.com/Hou1464851806/AgenticVideoProject/issues/4) [T03] 分片上传视频并看到待验证状态 | P0 | #3 | 实现与验证完成，见 [记录](verification/issue-4.md) |
| [#5](https://github.com/Hou1464851806/AgenticVideoProject/issues/5) [T04] 验证视频后在素材库定位播放 | P0 | #4 | 实现与验证完成，见 [记录](verification/issue-5.md) |
| [#6](https://github.com/Hou1464851806/AgenticVideoProject/issues/6) [T05] 断网、暂停与刷新后继续原上传 | P0 | #4 | 实现与验证完成，见 [记录](verification/issue-6.md) |
| [#7](https://github.com/Hou1464851806/AgenticVideoProject/issues/7) [T06] 取消或过期上传并查看残留清理 | P1 | #4 | 实现与验证完成，见 [记录](verification/issue-7.md) |
| [#8](https://github.com/Hou1464851806/AgenticVideoProject/issues/8) [T07] 超限或空间不足时阻止上传并解释原因 | P1 | #4 | 实现与验证完成，见 [记录](verification/issue-8.md) |
| [#9](https://github.com/Hou1464851806/AgenticVideoProject/issues/9) [T08] 上传后自动生成可定位的语音证据 | P0 | #5 | 实现与验证完成，见 [记录](verification/issue-9.md) |
| [#10](https://github.com/Hou1464851806/AgenticVideoProject/issues/10) [T09] 查找人名、数字和原句并跳转核查 | P0 | #9 | 实现与验证完成，见 [记录](verification/issue-10.md) |
| [#11](https://github.com/Hou1464851806/AgenticVideoProject/issues/11) [T10] 用主题和近义表达检索视频内容 | P1 | #10 | 实现与验证完成，见 [记录](verification/issue-11.md) |
| [#12](https://github.com/Hou1464851806/AgenticVideoProject/issues/12) [T11] 查询画面与可见文字并核查观察时刻 | P1 | #11 | 实现与验证完成，见 [记录](verification/issue-12.md) |
| [#13](https://github.com/Hou1464851806/AgenticVideoProject/issues/13) [T12] 在预算内复查候选，超预算先询问 | P1 | #12 | 实现与验证完成，见 [记录](verification/issue-13.md) |
| [#14](https://github.com/Hou1464851806/AgenticVideoProject/issues/14) [T13] 只恢复失败分析阶段并重建检索索引 | P1 | #12 | 实现与验证完成，见 [记录](verification/issue-14.md) |
| [#15](https://github.com/Hou1464851806/AgenticVideoProject/issues/15) [T14] 在独立制作会话中对话查询素材 | P0 | #10 | 实现与验证完成，见 [记录](verification/issue-15.md) |
| [#16](https://github.com/Hou1464851806/AgenticVideoProject/issues/16) [T15] 刷新或断线后恢复对话进度与结果 | P1 | #15 | 已完成；后端 95 全通过，浏览器完整回归 68/69、剩余模型预算用例独立复验通过，双轴审查 PASS；见 [记录](verification/issue-16.md) |
| [#17](https://github.com/Hou1464851806/AgenticVideoProject/issues/17) [T16] 停止对话任务并关联原需求重试失败 | P1 | #15 | 已完成；后端100项全通过，浏览器完整回归71/72、剩余真实模型回执不确定用例独立复验通过，双轴审查PASS，见 [记录](verification/issue-17.md) |
| [#18](https://github.com/Hou1464851806/AgenticVideoProject/issues/18) [T17] 纠正制作需求简报并回答关联澄清 | P1 | #15 | 已完成；后端107项全通过，浏览器完整回归74/75、竖屏混剪模型预算用例独立复验通过，双轴审查PASS，见 [记录](verification/issue-18.md) |
| [#19](https://github.com/Hou1464851806/AgenticVideoProject/issues/19) [T18] 长对话保留硬条件并分页查看完整历史 | P1 | #18 | 待实施 |
| [#20](https://github.com/Hou1464851806/AgenticVideoProject/issues/20) [T19] 从讲话视频生成一条可核查的短视频方案 | P0 | #15 | 实现与验证完成，见 [记录](verification/issue-20.md) |
| [#21](https://github.com/Hou1464851806/AgenticVideoProject/issues/21) [T20] 一次生成多条短视频成片定义 | P1 | #20 | 待实施 |
| [#22](https://github.com/Hou1464851806/AgenticVideoProject/issues/22) [T21] 手动调整片段并从任意历史版本继续 | P1 | #20 | 待实施 |
| [#23](https://github.com/Hou1464851806/AgenticVideoProject/issues/23) [T22] 对话替换指定片段并保留其他内容 | P1 | #18, #20 | 已实现，验证见 [记录](verification/issue-23.md) |
| [#24](https://github.com/Hou1464851806/AgenticVideoProject/issues/24) [T23] 手动生成带原声与字幕的低清效果预览 | P0 | #20 | 实现与验证完成，见 [记录](verification/issue-24.md) |
| [#25](https://github.com/Hou1464851806/AgenticVideoProject/issues/25) [T24] 确认具体版本后明确导出并下载一条成片 | P0 | #24 | 实现与验证完成，见 [记录](verification/issue-25.md) |
| [#26](https://github.com/Hou1464851806/AgenticVideoProject/issues/26) [T25] 批量导出选定成片并展示部分完成 | P1 | #21, #25 | 待实施 |
| [#27](https://github.com/Hou1464851806/AgenticVideoProject/issues/27) [T26] 按需求跨素材编排并导出基础混剪 | P1 | #25 | 已完成；后端 86 / 浏览器 66 全通过，双轴审查 PASS；见 [验证记录](verification/issue-27.md) |
| [#28](https://github.com/Hou1464851806/AgenticVideoProject/issues/28) [T27] 隐藏、恢复和安全删除素材 | P1 | #20 | 待实施 |
| [#29](https://github.com/Hou1464851806/AgenticVideoProject/issues/29) [T28] 清理过期预览并按需重新生成 | P1 | #24 | 待实施 |
| [#30](https://github.com/Hou1464851806/AgenticVideoProject/issues/30) [T29] 回收派生媒体后仍能核查历史证据 | P1 | #12, #20 | 待实施 |
| [#31](https://github.com/Hou1464851806/AgenticVideoProject/issues/31) [T30] 查看分类存储、模型用量和费用预警 | P1 | #15 | 已完成；后端 83 / 浏览器 64 全通过，双轴审查 PASS；见 [验证记录](verification/issue-31.md) |
| [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) [T31] 在故障与权限边界下验收三条用户主线 | P2 | #6, #7, #8, #13, #14, #16, #17, #19, #22, #23, #26, #27, #28, #29, #30, #31 | 待实施 |
| [#33](https://github.com/Hou1464851806/AgenticVideoProject/issues/33) [T32] 从说明启动、测量并备份恢复完整系统 | P2 | #32 | 待实施 |

单票完成以对应实现、必要真实验证和独立 Git 提交为依据；ready-for-agent 标签本身不代表前置完成。所有票完成后，代码质量与需求覆盖分别审查，修复后重跑全部验证。
