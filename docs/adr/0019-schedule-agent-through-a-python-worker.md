---
status: accepted
---

# 通过单实例 Python Worker 调度 Agent

2026-10-16 用户确认 CL-Q9：浏览器继续只访问 Java；Java 持久接纳消息与任务，经现有 Outbox／RabbitMQ 通知单实例 Python Worker，再通过内部接口交接执行身份、固定输入、进度和结果。Worker 调用独立 Agent 核心，不将队列和 Java 业务协议引入图内决策；CLI 保留开发与验收用途。

相较另加 FastAPI 来接收启动请求，这一选择复用已有持久任务和消息基础设施，减少一条启动链路；代价是必须补齐新版任务交接、恢复及结果发布，不能直接启用已退役的 Worker 协议。无需同时引入 Celery 或第二套任务数据库；checkpoint 与业务状态继续按 ADR-0007 分别管理。本决定补充 ADR-0016／0017，不表示 Worker 已实现。
