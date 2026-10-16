# 设计文档入口

Q58 已确认首期 MVP 整体设计；2026-10-12 补充确认统一自然语言视频工作流程。已有实现和验证进度见 [实施状态](implementation-status.md)，当前可用能力见 [功能清单](current-features.md)。当前运行形态以 M1 说明和第五阶段验收为准，旧对话交付作为历史记录保留。

完整需求已发布为 [GitHub 总 Issue #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)，标记 `ready-for-agent`。

| 阅读目的 | 文档 |
| --- | --- |
| 首版用户能力、完整需求与验收边界 | [完整需求](requirements.md) |
| 已确认的统一聊天流程、九项决定与代码职责 | [统一自然语言设计](natural-language-workflow-design.md) |
| 当前实际功能与待实施进度 | [功能清单](current-features.md)、[实施状态](implementation-status.md) |
| 已认可方向的前端布局、交互与 UI 重构设计，以及逐步改动范围、验收和回归要求（步骤 1～4 已完成，步骤 5～6 待实施） | [前端重构设计](frontend-redesign-design.md)、[分步实施计划](frontend-redesign-implementation-plan.md)、[步骤 1 基线](verification/frontend-redesign-step1.md)、[步骤 2 验证](verification/frontend-redesign-step2.md)、[步骤 3 验证](verification/frontend-redesign-step3.md)、[步骤 4 验证](verification/frontend-redesign-step4.md) |
| 目标、服务与完整链路 | [系统架构](architecture.md) |
| 术语与决定依据 | [领域术语](../GLOSSARY.md)、[访谈记录](design-interview.md)、[ADR](adr/) |
| Agent自治、工具与智能验收 | [Agent设计](agent-design.md) |
| LangGraph 节点、状态、错误恢复、共享预算与事件等待的具体设计 | [Agent 运行设计](langgraph-agent-runtime-design.md)、[官方运行语义核对](research/langgraph-runtime-semantics.md) |
| Python Agent 独立重写的最终设计、边界与 MVP 验收标准 | [Agent MVP 设计](agent-mvp-design.md) |
| Java 单体基础分析、四个真实读取工具、Python 接入与验收记录（已确认，M1 已验收） | [Java 工具 M1 设计](java-tools-m1-design.md) |
| M2 已确认的真实草稿、校验、显式保存设计及阶段进度 | [M2 最终设计](java-tools-m2-design.md)、[阶段 1 契约与上下文](verification/java-tools-m2-stage1.md)、[阶段 2 Java 校验](verification/java-tools-m2-stage2.md)、[阶段 3 显式保存](verification/java-tools-m2-stage3.md)、[阶段 4 Python 制作](verification/java-tools-m2-stage4.md)、[阶段 5 真实验收](verification/java-tools-m2-stage5.md) |
| M4 已确认的 CLI 显式预览、接口契约、文件范围、实施计划及验收标准；M3 按需细读后移 | [M4 最终设计](java-tools-m4-design.md)、[完整实施与验收记录（步骤 1～5 已完成）](verification/java-tools-m4.md) |
| 持续会话、制作草稿生命周期与浏览器接入（整体设计已确认，第 1～6 步身份／契约、Python 生命周期、Java 事务、Worker／MQ 恢复、草稿预览及浏览器衔接已实施，第 7 步实现／自动验收及本地更新完成，人工音画验收待体验） | [完整设计、实施顺序与验收](conversation-lifecycle-browser-design.md)、[第 1 步验证与交接](verification/conversation-lifecycle-step1.md)、[第 2 步验证与交接](verification/conversation-lifecycle-step2.md)、[第 3 步验证与交接](verification/conversation-lifecycle-step3.md)、[第 4 步验证与交接](verification/conversation-lifecycle-step4.md)、[第 5 步验证与交接](verification/conversation-lifecycle-step5.md)、[第 6 步验证与交接](verification/conversation-lifecycle-step6.md)、[第 7 步阶段验收及部署](verification/conversation-lifecycle-step7.md)、[用户接受草稿后冻结版本](adr/0018-freeze-plan-revisions-after-draft-acceptance.md)、[Python Worker 调度](adr/0019-schedule-agent-through-a-python-worker.md) |
| Java 后端结构、模型与运行说明（随 M1 实施维护） | [后端 README](../backend/README.md) |
| 独立 Agent CLI、重启恢复、真实 DeepSeek 与本机 Langfuse 的验收证据 | [运行说明](../agent-service/README.md)、[MVP 验收记录](verification/agent-mvp.md) |
| DeepSeek Flash 模型名称、OpenAI 兼容请求与 LangChain 客户端核对 | [Flash 适配核验](research/deepseek-flash-adapter-verification.md) |
| 完整改进目标、用户故事、实现决定与验收 | [视频理解与 Agent 改进规格](video-understanding-agent-improvement-spec.md)、[改进总 Issue #43](https://github.com/Hou1464851806/AgenticVideoProject/issues/43) |
| 分析、时间证据与混合检索 | [视频理解与检索](video-understanding-retrieval.md) |
| VideoRAG 实现、成本与本项目接入取舍 | [VideoRAG 研究评估](research/videorag-evaluation.md) |
| VideoAgent 论文／源码、时间记忆与受控工具循环的借鉴设计 | [VideoAgent 研究与设计](research/videoagent-evaluation.md) |
| 百炼分析模型与 DeepSeek Flash 推理的视频理解、补查与评测设计草案 | [视频理解设计](research/bailian-video-understanding-design.md) |
| 面向用户目标的 Agent 循环、统一时间线、工具与完成判断草案 | [视频 Agent 与时间线设计](research/goal-driven-video-agent-design.md) |
| 分片、恢复、限额与清理 | [上传设计](upload-design.md) |
| 对话、上下文与工作区 | [对话与前端](conversation-design.md) |
| 方案内容、字幕与渲染约束 | [方案契约](edit-plan-contract.md) |
| 表结构、引用保护与事务 | [数据库规格](database-design.md) |
| 浏览器／内部端点与错误 | [HTTP契约](api-contract.md) |
| 任务状态与执行恢复 | [任务协议](task-protocol.md) |
| MQ、SSE与快照恢复 | [事件协议](event-protocol.md) |
| 开发依赖与完成标准 | [任务清单](development-backlog.md) |
| 功能、故障与本机测试 | [实施与验收](implementation-plan.md) |

已确认的产品／架构边界与默认实现字段分别标识；数值参数和第三方能力以实际联调结果固定，不能把文档生成或格式检查当作运行验证。

- [Java 工具 M1 第二阶段实施与验证](verification/java-tools-m1-stage2.md)
- [Java 工具 M1 第三阶段实施与验证](verification/java-tools-m1-stage3.md)
- [Java 工具 M1 第四阶段 Python 接入与验证](verification/java-tools-m1-stage4.md)
- [Java 工具 M1 第五阶段综合验收与故障验证](verification/java-tools-m1-stage5.md)
- [Java 工具 M1 完整收尾审查](verification/java-tools-m1-closeout.md)
