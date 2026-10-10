# 视频编排剪辑系统设计访谈

状态：设计访谈进行中。记录已确认边界、技术事实与待确认项；各轮记录以最新修订为准，尚未开始功能实现。

## 用户提出的需求

- 用户创建项目，并将素材上传至项目素材库。
- 用户通过多轮对话与 Agent 形成编排剪辑方案。
- 用户可请求系统根据方案合成渲染视频。
- 目标场景包含长视频剪成多条短视频、多素材合成一个视频、通过对话检索视频中的场景和文字。

## 用户提出的技术设想

- 后端：Spring Boot、PostgreSQL、Qdrant、Redis、PGSTY Silo（拟用作兼容 S3 的对象存储）、RabbitMQ。
- 前端：Vue 3、TypeScript。
- Agent 服务：Python、FastAPI、LangGraph。
- 模型：Qwen ASR、VLM、Embedding，以及 DeepSeek 对话与推理模型；希望使用 OpenAI 风格接口。
- 具体模型版本、模型提供方、部署方式和接口能力仍待核验与选择。

## 已核验的技术事实（2026-10-09）

- [PGSTY Silo](https://github.com/pgsty/silo) 是兼容 S3 的对象存储，源自 MinIO 社区分支。
- [百炼 Embedding 兼容接口文档](https://help.aliyun.com/zh/model-studio/embedding-interfaces-compatible-with-openai) 区分文本与多模态向量模型；qwen3-vl-embedding 等多模态模型不支持 OpenAI 兼容接口。因此需要先确定模型与提供方，再设计内部模型调用协议。
- [百炼 Qwen ASR API](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference) 的部分模型采用 Chat 消息中的音频输入；不能从“OpenAI 兼容”推导出使用 audio/transcriptions 或具备词级时间戳。剪辑定位能力需独立核验。
- [百炼非实时 ASR 文档](https://help.aliyun.com/zh/model-studio/non-realtime-speech-recognition-user-guide) 明确：qwen3-asr-flash-filetrans 的 DashScope 异步 API 返回句级时间戳，并可通过 enable_words 获取字／词级时间戳；Qwen3-ASR-Flash 的 OpenAI 兼容 API 不返回时间戳。具体剪辑边界准确度仍需实测。
- [ffprobe](https://ffmpeg.org/ffprobe.html) 可用于媒体探测；[FFmpeg](https://ffmpeg.org/ffmpeg.html) 可执行媒体转换与渲染。是否使用二者及如何部署执行端仍待决策。
- [FFmpeg concat filter](https://ffmpeg.org/ffmpeg-filters.html#concat) 需要显式统一分辨率等参数；各段对应的流数量须一致，起始时间戳须为零。不同帧率可以拼接但会产生可变帧率；concat demuxer 的输入兼容约束不同。

已确认设置独立媒体任务执行端，Agent 提交可校验的方案修改；服务边界、统一方案模型和交互方式已明确，任务协议基线已整理于 task-protocol.md。

## 第一轮已确认的边界

- 首期面向个人用户，目标为可部署、可演示、可实际使用的 MVP。
- 长视频拆成多条短视频与多素材混剪都在目标范围内；第二轮确认以讲话类长视频拆短视频先跑通，再实现基础混剪。
- 交互采用多轮对话、片段列表与预览；允许用户手动调整片段时间和片段顺序。
- 用户必须确认方案并明确表示导出，系统才开始正式渲染。参见 ADR-0001。
- 通过模型 API 获得模型能力；具体提供方与模型仍未确定。

## 第二轮已确认的边界

- 以访谈、课程、讲解类视频起步，先实现语义选段和拆短视频，再实现基础多素材混剪。
- 首期支持选段、调整起止时间、重排、硬切拼接、保留原声和自动字幕。
- 修改后的方案视为新版本，需要重新确认；保留旧版本与修改关系，支持回退至任意版本。
- Agent 修改期间禁止用户手动调整，以自然语言交互作为主要编排方式。参见 ADR-0002。
- 支持片段预览与低清合成效果预览；第四轮明确低清预览由用户手动发起。
- 上传后自动做基础分析，细致分析按需触发；检索结果必须附素材、时间范围和可播放证据。
- 起步限制：单视频 60 分钟、单项目 10 个视频、正式渲染并发 1；这些限制需可配置，并保留规模扩展能力。
- 初期仅使用用户本机 MacBook，要求 Docker Compose 部署；后续确认暂不设置固定模型 API 月预算，记录用量并提供可配置预警。

## 本机事实（只读核验）

- macOS 15.7.1、arm64、16 GiB 物理内存，Docker 命令已安装。实际运行资源与性能仍需以部署后的测量为准。
- Docker Desktop 正在运行 Linux aarch64 虚拟机，分配 8 CPU、约 7.65 GiB 内存；该资源由已有容器与本项目共享。Docker 支持 Apple Silicon 与资源限额配置：[安装要求](https://docs.docker.com/desktop/setup/install/mac-install/)、[资源设置](https://docs.docker.com/desktop/settings-and-maintenance/settings/)。
- CPU FFmpeg 是本机普通 Compose Linux 容器的部署基线建议；不能默认能调用宿主 macOS VideoToolbox。Docker 官方 GPU passthrough 支持范围与此不同：[GPU 文档](https://docs.docker.com/desktop/features/gpu/)。原生 macOS 执行端能否提升速度仍需实测。

## 模型费用参考（2026-10-09）

- [百炼官方价格](https://help.aliyun.com/zh/model-studio/model-pricing)：华北2（北京）qwen3-asr-flash-filetrans 为 0.00022 元/输入音频秒，一小时调用一次的 ASR 单项费用为 0.792 元，未计免费额度、其他模型与重复调用。
- VLM 费用还受模型、抽帧数量、图像分辨率、重复分析和输出长度影响；不能据 ASR 单价推断完整流程费用。
- DeepSeek 费用应按选定模型的实际用量与[官方价格](https://api-docs.deepseek.com/quick_start/pricing/)统计。内部需要区分计费用量与估算费用；未获得提供方计费用量时应标记估算。
- 已确认复用素材分析、限制自动循环、记录用量与估算费用，并提供可配置预警；暂不设置固定月预算。

## 第三轮已确认的边界与修订

- 接受项目 → 多份方案 → 方案版本 → 多个成片定义 → 有序片段的统一模型；修改一个输出产生整个方案的新版本。参见 ADR-0003。
- 修订恢复方式：用户自由切换已有版本，切换本身不新增版本；修改完成后的新版本追加至历史末尾，修改来源必须保留。
- 接受内部统一模型能力接口，允许提供方原生协议；第四轮明确适配层放在 Python Agent 服务内，首期不部署独立网关。
- 接受 16:9／9:16 输出、等比例缩放与留边填充、MP4 H.264/AAC、720p／1080p 正式输出与低清效果预览。
- 接受付费用量和估算费用可见、分析复用、自动调用上限与可配置预警，暂不设置固定月预算。
- 本轮曾比较 Java 与 Python Worker；第四轮确认采用独立 Spring Boot 媒体 Worker。

## 第四轮已确认的边界与修订

- 采用 Spring Boot 业务后端、独立 Spring Boot 媒体 Worker 和 Python Agent 服务，接受第三轮后提出的职责划分与内部模型适配模块。参见 ADR-0004。
- 编辑锁以方案为单位；Agent 修改时允许查看和切换历史版本，禁止手动编辑和再次发起修改，允许取消当前修改。Agent 完成后提示新版本，保留用户正在查看的历史版本。
- 确认绑定版本；已确认旧版本切换后仍保留确认，但每次导出需要明确指令和输入有效性校验。
- 修订预览生成时机：用户手动发起低清合成效果预览，方案修改后不自动生成。第五轮确认预览复用与容量／过期清理策略。
- 视觉检索采用基础分析后定位候选时间段，并按需加密抽帧复查；首期不承诺完整识别每一帧中的所有文字。

## 第五轮已确认的边界

- 预览按版本、输出与规格复用，重复点击合并任务；每项目起步缓存上限 2 GB，7 天未访问可清理，均可配置。优先清理最久未使用的预览，跳过正在生成或播放中的文件，清理后按需重建。
- 被任一方案版本引用的素材允许隐藏，但禁止直接彻底删除；彻底删除前需处理历史引用并说明影响。
- 正式成片只由用户明确删除，下载不触发删除；界面分别展示素材、预览和成片存储占用。参见 ADR-0005。
- 任务状态持久化；临时故障有限重试，参数／素材错误直接失败；重启后恢复未完成任务，取消本地执行时停止进程并清理临时文件，Agent 失败或取消后保留原版本并释放编辑锁。
- 支持多个独立账号、用户名密码登录，项目、素材、方案、检索与任务按用户隔离；首期个人项目，不包含团队共享。
- 一个项目允许多个独立会话，明确当前方案和版本；检索默认本项目且可限定素材，仅检索不必创建方案，切换操作对象需显式显示。

## 当前待确认事项

- 系统架构、Agent 内部设计以及 Q42–Q47 视频理解与检索细化均已逐项确认；等待 Q48 对整合后的 MVP 设计基线进行共同理解核对。Q48 取代此前尚未答复的 Q34、Q41 整体核对问题。
- Q48：确认 architecture.md、agent-design.md、video-understanding-retrieval.md、task-protocol.md、edit-plan-contract.md 和 implementation-plan.md 整体准确表达目标与约束；数值参数实测固定、能力缺口回到设计讨论，不将逐项选择等同于已经授权代码实现。
- 具体模型 ID、框架／镜像版本、超时与抽帧等参数在实施阶段核验固定，见 implementation-plan.md；当前未开始代码实现。

## 第六轮已确认的架构与验收

- 普通业务和受控工具采用 HTTP，耗时任务由 RabbitMQ 派发，SSE 展示对话与进度。
- Spring Boot 在同一 PostgreSQL 事务保存任务和 Outbox 事件，可靠投递消息；Worker／Agent 通过内部业务接口领取、心跳和提交结果，防止迟到尝试覆盖任务状态。参见 ADR-0006。
- PostgreSQL 保存业务、分析证据、方案、确认、任务与用量；Qdrant 为可重建索引，Redis 为缓存与运行辅助，Silo 保存媒体文件，RabbitMQ 派发任务。
- LangGraph checkpoint 在 PostgreSQL 的独立数据范围，由 Python 管理，与 Spring 管理的业务版本分开。参见 ADR-0007。
- 首期采用带时间范围的文本证据、文本 Embedding 与关键词通道，直接图像／视频向量检索留待后续。参见 ADR-0008。
- Qwen 优先百炼，DeepSeek 使用官方 API；模型、地域和参数配置化，记录分析及索引版本。
- 首期验收覆盖讲话视频拆短视频、多素材基础混剪、内容查询证据三条功能闭环，以及版本、取消／重启恢复、隔离、清理和费用统计；先讲话视频闭环，再基础混剪。
- 当前整合产物：architecture.md、task-protocol.md、edit-plan-contract.md、implementation-plan.md、GLOSSARY.md 与 ADR-0001 至 ADR-0008。

## 新增 Agent 内部设计分支

用户要求在当前阶段确定自主 Agent 与 Agentic Workflow 的选择、工具调用、行为模式与智能程度。Q35 至 Q39 已确认受控流程中的有限自主单 Agent、关键条件澄清、预算内主动复查和基于证据的编排／局部修改／自检／诚实失败，参见 ADR-0009。Q40 确认分阶段工作流、按需轻量规划、阶段内受限 ReAct，参见 ADR-0010；内部策略变化不产生业务版本，既有渲染授权和业务服务边界不变。

## 新增视频理解与检索细化分支

用户要求进一步明确视频分析、理解、向量化的离线流程、向量与 BM25 混合检索及其他考量。Q42–Q47 均已确认，详见 video-understanding-retrieval.md：

- Q42：原始观察证据、检索单元、素材摘要分层；语音、画面、可见文字区分来源，保留时间与版本，检索边界不直接当作切点。
- Q43：Qwen 稠密向量＋Qdrant 原生中文 BM25＋RRF；原文短语匹配补充，不增加独立搜索引擎。替代此前关键词主通道的包含查询基线，参见 ADR-0011。
- Q44：先验证基础召回，预留精排接口，仅在正确候选已经召回而排序差时评估专用精排模型。
- Q45：语音就绪可先开放查询与选段，视觉能力分阶段开放；独立重试，新索引验证后发布有效代次。
- Q46：未命中说明已分析范围，预算内主动补查，高覆盖需求明确限制，超预算时澄清。
- Q47：分析证据与版本随素材保留，保护历史引用；派生音频和普通代表帧采用独立容量的可重建缓存。阶段发布与保留权衡参见 ADR-0012。

本轮技术事实经官方来源核验：Qdrant 原生中文 BM25、RRF 与 v1.19 的 IDF corpus 范围；v1.19.2 在 2026-10-05 已发布且官方镜像覆盖 arm64；ASR 原生时间戳与本地音频临时上传 REST 路径；FFmpeg 代表帧与镜头变化的区别；Qwen VLM 的画面输入不包含音频理解。具体来源与尚待实际联调的边界均列于设计文档。

## 已确认服务职责

- 共享基础能力：素材探测、带时间定位的转写、基础画面分析、分析结果与检索索引。两条剪辑主线复用这些结果，但语义检索与视觉检索可能需要不同证据和分析粒度。
- 业务后端：项目、素材、方案版本、确认、任务、权限与业务状态的权威入口；优先采用 Spring Boot 模块化单体。
- Agent 服务：对话、意图理解、检索工具调用与方案提议；通过业务接口提交修改。
- 媒体 Worker：采用独立 Spring Boot 应用，执行可排队的本地素材处理与 FFmpeg 预览／正式渲染。
- 本地媒体操作与模型内容理解分开：Java Worker 做探测、音频／画面提取与渲染；Python Agent 服务内的固定内容分析流程做 ASR、VLM 与向量化，并复用对话 Agent 的模型适配模块。
- 服务分离不等于部署多台机器；初期拟由 Docker Compose 在本机运行，执行端保留独立扩容能力。

## Worker 语言核验（2026-10-10）

- Java [Process](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html) 与 Python [subprocess](https://docs.python.org/3/library/subprocess.html) 均能启动、读取输出与管理 FFmpeg 子进程；编码速度不能由控制语言直接推断。
- Spring Boot 官方支持[非 Web 应用](https://docs.spring.io/spring-boot/how-to/application.html)，Spring AMQP 支持[消费者并发配置](https://docs.spring.io/spring-amqp/reference/amqp/listener-concurrency.html)，适合独立任务执行端。
- [百炼长音频 API](https://help.aliyun.com/zh/model-studio/non-realtime-speech-recognition-user-guide) 同时提供 Java／Python 示例；不存在调用托管 ASR 必须使用 Python 的限制。
- 若未来采用依赖 Python 的本地模型或视觉算法，Python 执行端集成更直接；当前模型全托管 API、媒体操作以 FFmpeg 为主，第四轮确认采用独立 Java Worker。
- 进程管理必须持续消费输出、设置超时并在取消时确认进程退出；任务清理与业务 API 隔离同样重要，与采用哪种语言无关。

## 模型适配与网关的区别

- 模型适配层统一 Chat、Transcription、Vision、Embedding 等能力及异步模型任务生命周期，隔离提供方协议。
- 独立网关还引入网络接口、鉴权和单独部署；是否需要该边界取决于调用方与运维需求。
- 已确认在 Python Agent 服务内隔离模型适配模块，内容理解流程与对话复用它；首期不增加独立网关容器。
- Java 执行端直接调用模型和独立模型网关超出首期范围；若未来确有需要，再评估能力接口与部署边界。

## 预览保留与任务恢复事实（2026-10-10）

- [Silo 生命周期指南](https://github.com/pgsty/silo/blob/main/docs/bucket/lifecycle/README.md) 提供按前缀、日期或对象年龄删除的规则；规则不会自动理解数据库中的业务引用。
- [Spring 调度](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) 可支持应用级周期清理；当前清理实现候选为业务后端检查使用状态与清理资格，再删除对象并更新记录，保留策略已确认。
- [RabbitMQ 消费确认](https://www.rabbitmq.com/docs/confirms) 明确未确认消息可能重投递，因此任务协议需要幂等处理，不能假定一条消息只执行一次。
- 已确认保留完整方案历史，低清预览按版本与预览配置复用，并按容量或过期清理；清理后按需重建。原始素材与正式成片采用独立保留策略。

## 服务交互事实与候选（2026-10-10）

- [RabbitMQ 可靠性](https://www.rabbitmq.com/docs/reliability) 与确认机制不会替代业务幂等；[Outbox 原理](https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/)可用于解决数据库提交与消息发送之间的缺口。候选：业务任务和待发送事件同事务保存，轮询发布至 RabbitMQ，无需额外引入 Kafka 或 Debezium。
- [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence) 保存 thread 的执行状态；候选使用 PostgreSQL 持久化，独立于 Spring 管理的方案版本与确认记录。
- [Qdrant 多租户](https://qdrant.tech/documentation/tutorials/multiple-partitions/)支持 payload 范围过滤；过滤条件必须由已鉴权后端强制注入，不能仅依赖模型或浏览器传来的 user_id／project_id。
- [PostgreSQL 文本解析器](https://www.postgresql.org/docs/18/textsearch-parsers.html)没有默认中文词级分词的保证；关键词通道需要明确中文处理方式并验证，不能直接假定内置全文检索满足中文素材要求。
- [S3 预签名 URL](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)属于限时持有者授权；候选由业务后端鉴权后签发指定对象与操作的短期 URL。Silo 的实际兼容行为需集成验证。

## 决策树

- 产品目标与首期边界〔已确认〕
  - 目标用户与交付目标
    - 后续：身份与权限、团队协作、部署与运营要求
  - 首期场景优先级
    - 后续：内容理解粒度、检索能力、剪辑操作集合、验收案例
  - 用户修改方案的方式
    - 后续：方案与时间线的关系、预览、方案版本与编辑冲突
  - Agent 的执行权限
    - 后续：确认节点、任务生命周期、取消与重试、成本约束
  - 首期资源边界〔已确认，参数可配置〕
    - 后续：模型部署、异步处理、存储容量、吞吐与等待时间
- 领域模型与模块职责〔已确认〕
  - 素材、分析结果、片段、方案、渲染任务与成片的关系
  - Spring Boot、Agent 服务和媒体处理执行端的职责与交互
- Agent 内部设计〔已确认〕
  - 有限自主单 Agent、工具边界、澄清与预算内补查
  - 分阶段工作流、按需轻量规划、受限 ReAct 与退出条件
  - 证据编排、局部修改、自检和诚实失败的验收
- 视频理解与检索细化〔已确认〕
  - 原始观察、检索单元、摘要和时间定位语义
  - 中文 BM25、稠密向量融合和精排时机
  - 分阶段就绪、增量分析与索引代次发布
  - 未命中、采样覆盖与按需深入检索
  - 分析证据保留与派生音频／代表帧缓存
- 技术选型与执行链路〔架构已确认，版本与性能参数实施时核验〕
  - 媒体探测、内容分析、索引、编排校验、预览、渲染
  - 持久化、消息与状态一致性、故障恢复
- 验收与实施顺序〔已确认，待整体核对〕

## 文档规则

已明确的领域术语记入根目录 GLOSSARY.md；有实际权衡且改变成本较高的已确认决策记入 docs/adr/。
