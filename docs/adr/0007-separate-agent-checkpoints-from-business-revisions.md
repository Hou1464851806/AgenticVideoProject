---
status: accepted
---

# 业务方案版本与 Agent checkpoint 分别持久化

PostgreSQL 是方案、确认和任务等业务状态的权威来源，LangGraph checkpoint 在独立的数据范围保存 Agent 运行状态，由 Python 管理。与将 graph state 直接当作方案历史相比，分离可以保证用户版本切换、确认与导出不因 Agent 恢复或执行回溯而被覆盖；代价是执行状态与业务提交需要显式协调。Qdrant 是可重建索引，Redis 是可失效辅助数据，均不替代业务持久状态。
