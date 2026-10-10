---
status: accepted
---

# 独立 Java 媒体执行端与 Python Agent 服务

系统采用独立部署的 Spring Boot 业务后端、Spring Boot 媒体 Worker 与 Python Agent 服务，初期由本机 Docker Compose 运行。业务后端控制业务数据、方案版本、确认与任务状态；Java Worker 负责本地媒体探测、音频／画面提取及 FFmpeg 预览和正式渲染；Python 服务负责模型调用、固定内容分析流程、检索编排与对话方案生成，内部模型适配层由分析和对话复用，首期不单独部署模型网关。当前媒体工作以外部 FFmpeg 和托管模型 API 为主，采用 Java Worker 可复用 Java 任务协议与基础设施模块；代价是媒体执行和模型理解跨服务协作，未来若引入 Python 本地算法还需重新评估执行端边界。业务应用与 Worker 可在同一 Maven 多模块工程中共享必要协议，但独立启动，以便隔离资源并单独扩容。
