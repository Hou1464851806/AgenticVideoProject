# Java 后端

Java 后端负责素材、基础视频分析、证据检索、真实工具治理及媒体执行；Python Agent 负责目标理解、工具决策、补答和运行恢复。

M1 最终设计已获用户确认；按用户要求暂不开始实现，应用尚未迁入目标结构。完整方案及验收计划见 [Java 工具 M1 设计](../docs/java-tools-m1-design.md)，架构决定见 [ADR-0017](../docs/adr/0017-move-baseline-analysis-and-reading-tools-into-java.md)。

## 当前结构

```text
backend/
├── README.md
├── business-api/   # Spring Boot 业务入口、持久数据及现有任务协议
└── media-worker/   # 独立媒体执行应用：探测、提取、抽帧及渲染
```

当前 Maven 聚合入口在仓库根 `pom.xml`。旧 Python 分析 Worker 已停止接入，独立 Agent 默认使用 Mock；当前启动旧业务栈不代表真实 ASR、视觉和 Agent 工具链路已经恢复。既有事实与重构缺口详见 M1 设计记录。

## M1 目标结构

将两个 Java 应用收敛为一个 Spring Boot 模块化单体，使用标准 Maven 目录：

```text
backend/
├── README.md
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/
    │   └── resources/
    └── test/
        ├── java/
        └── resources/
```

Java package 按素材/上传、媒体、分析、百炼适配、证据/检索、工具、任务及方案/渲染组织；认证、项目、存储等现有职责继续保留。模块直接调用少量业务入口，事务不跨外部模型请求或 FFmpeg 执行。

## 基础处理与模型

上传完成后自动进行媒体探测、音频提取及稀疏抽帧。ASR、VLM、Embedding 均由 Java 通过阿里云百炼 API 调用：

- ASR 处理完整音轨，保存语音区间和词级时间；无音轨明确记为不适用。
- VLM 处理有上限的代表帧，保存画面及可见文字的实际观察时刻，明确稀疏覆盖。
- Embedding 用于文档索引及查询向量；PostgreSQL 保存权威证据，Qdrant 保存可重建索引。

三个模型适配分别保留输入/输出和同步/异步协议，共享必要的 HTTP 与认证配置。基础处理分来源开放，索引失败复用已经生成的证据，不重新执行成功的模型分析。

本机基础设施沿用 PostgreSQL、Silo、Qdrant 和 RabbitMQ，模型凭据采用现有 `DASHSCOPE_API_KEY` 等配置；具体模型、区域、地址和运行配置在迁写时核对，并在实现后补全此处配置表。凭据不写入文档、日志或模型提示词。

## RabbitMQ 与后台任务

M1 保留并重构 RabbitMQ：Java 是模块化单体，任务生产者和消费者在同一 Spring Boot 应用，MQ 承担后台异步投递和积压。

```text
业务接纳事务：任务 + Outbox
→ 确认发布到 RabbitMQ
→ 有界消费者原子占用任务
→ 阶段结果/下一步意图落盘
→ ACK
```

媒体和分析使用两类工作队列，消息只携带任务等身份。数据库保存权威状态和回执；同一任务重复投递复用结果，安全临时失败有限重试，最终失败进入死信并保留数据库原因。付费提交不确定时停止自动重付。异步 ASR 保存提供方任务 ID，再经 MQ 派发到期查询步骤。

合并时移除旧跨进程 HTTP 领取/回报、Worker 租约和心跳，保留库内原子占用、执行代次和取消检查。四个 Agent 读取工具继续 HTTP 调用，Python 不直接消费 MQ。后续按需细读及制作可以复用这条后台任务路径。

实际重构后的确认、重复投递、有限重试、死信、broker 故障恢复和并发控制需要真实验证；本 README 当前记录目标方案，尚不代表新链路已经通过验收。

## 工具与 Python 接入

M1 真实模式通过 Python CLI 的 `JavaToolExecutor` 调用四个读取工具：

| 工具 | 职责 |
| --- | --- |
| list_assets | 固定范围内的素材及处理状态 |
| get_video_overview | 预生成时间导航目录及覆盖情况 |
| search_evidence | 混合召回并返回权威证据 |
| read_video_range | 按时间范围读取原始证据 |

同一次问答固定范围快照，补答和恢复沿用；来源未就绪说明缺口，不自动等待。检索通道独立降级，同一工具调用重放复用回执，付费提交不确定时不自动重付。未接入能力明确不可用，真实模式不回退 Mock。

按需细读、真实草稿校验和 Agent 制作接入属于后续里程碑。现有预览/导出规则及 FFmpeg 能力在合并单体时保留。

## 构建、启动与验证

以下命令适用于当前仓库结构，在**仓库根目录**执行：

```sh
mvn -B -ntp verify
./scripts/start.sh
```

Java 集成测试需要 Docker，部分媒体测试需要对应执行环境。当前启动脚本的配置及基础设施说明见 [仓库 README](../README.md)。

实现后将核对并补充：新单体的构建/启动命令、FFmpeg 与字体依赖、工作目录、数据库与对象存储配置、百炼模型配置、真实工具 CLI 命令及可复现验证入口。此处规划中的结构和能力尚未运行验收。

真实验收使用仓库已有的带标签视频，检查语音、画面、文字、时间偏移、来源区分及语义召回，并验证重放、恢复、取消、检索降级和既有渲染。自动化测试与真实模型运行分别报告，缺凭据或跳过测试不能记为通过。

## 相关说明

- [M1 最终设计与实施计划](../docs/java-tools-m1-design.md)
- [Python Agent 运行说明](../agent-service/README.md)
- [后端分析与检索设计背景](../docs/video-understanding-retrieval.md)
- [方案与渲染契约](../docs/edit-plan-contract.md)

以上背景文档包含历史执行形态；M1 的新职责与范围以 M1 设计及 ADR-0017 为准。实施后在本 README 维护真实运行方式，并链接新的验收记录。
