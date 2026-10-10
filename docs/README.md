# 设计文档入口

Q58 已确认首期 MVP 整体设计。文档已进入详细规格，应用实现与外部能力联调尚未开始。

完整需求已发布为 [GitHub 总 Issue #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)，标记 `ready-for-agent`。

| 阅读目的 | 文档 |
| --- | --- |
| 首版用户能力、完整需求与验收边界 | [完整需求](requirements.md) |
| 目标、服务与完整链路 | [系统架构](architecture.md) |
| 术语与决定依据 | [领域术语](../GLOSSARY.md)、[访谈记录](design-interview.md)、[ADR](adr/) |
| Agent自治、工具与智能验收 | [Agent设计](agent-design.md) |
| 分析、时间证据与混合检索 | [视频理解与检索](video-understanding-retrieval.md) |
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
