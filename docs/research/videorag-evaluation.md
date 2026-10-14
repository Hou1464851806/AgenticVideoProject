# VideoRAG 上游实现研究与采用评估

研究日期：2026-10-14（Asia/Shanghai）。上游仓库：[HKUDS/VideoRAG](https://github.com/HKUDS/VideoRAG)，审查提交：[c412a093a820ef7a0e0dda31076ed871136198b3](https://github.com/HKUDS/VideoRAG/tree/c412a093a820ef7a0e0dda31076ed871136198b3)，提交日期 2026-03-18。论文为 [VideoRAG: Retrieval-Augmented Generation with Extreme Long-Context Videos，arXiv:2502.01549v1](https://arxiv.org/abs/2502.01549)，不是名称相近的其他 Video-RAG 论文。

研究仅阅读论文、官方仓库与模型官方说明；没有安装模型、调用付费 API 或运行性能基准。下文区分“论文陈述”“源码观察”和“设计推断”；调用数量是从代码推导的工作量，不是实测价格或时延。

## 1. 结论

**适合借鉴渐进观察与按问题补查的思路，不建议把整个 VideoRAG 当成当前项目的视频理解模块直接替换。** VideoRAG 的主目标是长视频、多视频知识问答。剪辑需要的词级边界、权限范围、分析代次和可验证引用，不能由它的问答结果替代。

最值得借鉴的是：初次分析保存可检索记录；查询定位少量片段；针对问题重新检查这些片段；再结合转写回答。实体图谱和原视频视觉向量，是可独立试验的额外召回通道，不能因为论文把它们组合起来，就把全部组件视为当前产品的必要条件。这是设计推断，依据如下。

## 2. 论文的双通道，和源码实际做的事情

**论文陈述：** 视频先分段，ASR 与 VLM 产生文本。一条通道利用 LLM 提取实体及关系，建立跨视频图谱并保留文本向量；另一条通道直接把视觉片段编码为多模态向量。查询结合实体检索、视觉检索、片段过滤与面向问题的细化描述。实验设置是 30 秒片段，初次 5 帧、再次 15 帧。评估覆盖 164 个视频、约 134.6 小时、602 个问题；问题由 NotebookLM 根据转写生成，主评估依赖 LLM 对答案进行比较评分。这不等于证明逐条事实、时间定位或剪辑边界都准确。[论文 §3–4](https://arxiv.org/html/2502.01549v1)

**源码观察：** 当前仓库有两份后端代码，而不是一套实现加两个界面。

| 项目 | `VideoRAG-algorithm` | `Vimo-desktop/python_backend` |
|---|---|---|
| 主要用途 | 研究实现、复现实验 | 桌面问答应用后端 |
| ASR | 本地 faster-distil-whisper-large-v3 | DashScope 云 ASR，界面默认 paraformer-realtime-v2 |
| Caption | 本地 MiniCPM-V-2_6-int4 | 云视觉模型，界面默认 qwen-vl-plus-latest |
| 文本 LLM／embedding | 多种配置；论文和示例使用 GPT-4o-mini／text-embedding-3-small | OpenAI 兼容配置，界面默认 GPT-4o-mini，embedding 为 text-embedding-3-small |
| 视觉向量 | 本地 ImageBind；代码直接 `.cuda()` | 共享 ImageBind HTTP 服务；Mac 代码选择 CPU |
| 存储 | JSON KV、NanoVectorDB、NetworkX GraphML | 同类本地存储，按聊天工作目录组织 |
| 调度 | 同步入口＋async 内部步骤＋多进程 | Flask API＋工作进程＋状态文件 |

来源：[算法入口](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/videorag.py)、[算法 LLM 配置](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_llm.py)、[Vimo 模型默认值](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/src/main/handlers/settings.ts#L69)、[Vimo API 构造配置](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/python_backend/videorag_api.py#L645)、[Mac ImageBind 设备选择](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/python_backend/videorag/_utils.py#L223)。

### 离线建库链路

`VideoRAG.insert_video()`：切成短片段并保存音频 → ASR → 初始 caption，同时重编码临时视频片段 → 保存段信息 → ImageBind 编码临时片段 → 删除临时缓存 → 文本分块 → chunk embedding → 实体／关系抽取与合并 → entity embedding → 保存各存储。

源码默认分段 30 秒、初始 5 帧、细化 15 帧、text chunk 1,200 tokens、实体补充抽取次数 1；原视频以文件路径保留。已有视频的去重键是文件 basename 截断第一个点，不能替代生产系统的素材 UUID／内容哈希。[算法入口与默认值](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/videorag.py)

抽帧不是连续观看视频：caption 使用 `linspace(..., endpoint=False)`；初次 30 秒段约取 0、6、12、18、24 秒，细化取约每 2 秒一帧。最后不足 5 秒的尾段并入前段，帧间距随之变化。所有图像被 resize 为 1280×720。VLM 接收转写与多帧图片，生成整段英语描述。[切片实现](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_videoutil/split.py)、[caption 实现](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_videoutil/caption.py)

视觉向量也不是完整保存视频：当前 feature 代码只向 ImageBind 传 `VISION`，没有同时编码音频。其固定依赖的数据函数默认取 5 个 2 秒子片段，每子片段时间采样 2 帧，再做空间裁剪。所谓“多模态”在这里主要是视频视觉与查询文本共享特征空间；它不能提供一个事件精确持续区间的证明。[feature.py](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_videoutil/feature.py)、[固定依赖 ImageBind data.py](https://github.com/facebookresearch/ImageBind/blob/3fcf5c9039de97f6ff5528ee4a9dce903c5979b3/imagebind/data.py#L269)

### 在线问答链路

实际调用是 `query()` → `aquery()` → `_op.videorag_query()`：

1. 原问题做 chunk dense retrieval；没有文本命中时普通问答路径直接失败。
2. LLM 改写实体查询 → entity embedding 检索 → 图中的一跳关系辅助选文本 chunk → 回溯片段。
3. LLM 改写视觉查询 → ImageBind 文本编码 → 视频视觉向量召回。
4. 两路片段去重合并 → 对每个候选执行 LLM 相关性过滤。
5. LLM 提取问题关键词 → 对保留片段重新解码 15 帧，生成面向问题的详细 caption。
6. 将详细 caption、转写、文本 chunk 交给 LLM，返回自然语言答案。

这套接口返回答案，**不是只返回证据的 Retriever**。`QueryParam.only_need_context` 虽然声明了字段，当前 `videorag_query()` 没有使用它绕过回答。要接入已有 Agent，需要拆出检索／补查结果接口。[查询源码](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_op.py)、[QueryParam](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/base.py)

需要单独保留的论文／源码差异：论文过滤公式写两路交集，当前代码取**并集**。过滤以输出包含 `yes` 作为通过条件；全部被否定时，代码退回使用全部召回片段。对生产证据系统应改为结构化相关性与不足状态，而不是直接照搬这一兜底策略。[算法查询](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_op.py)、[Vimo 查询](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/python_backend/videorag/_op.py)

## 3. 什么可以复用，什么不能直接当成事实层

**值得复用的思路：** 粗分析和细分析分开；以时间片段关联文本、画面与原视频；查询命中之后针对问题局部补查；文本与视觉分别召回；保留跨视频来源关系。这些都可用现有 ASR／VLM／数据库重新实现，不依赖完整引入图谱或 ImageBind。

**源码观察及设计推断：** 图谱是 LLM 对 caption＋转写的二次提炼。实体合并主要基于清洗后的大写名称，长描述再经 LLM 摘要；没有一个通用机制证明语义相同实体一定合并、冲突说法一定保留。实体图适合作为找到原记录的导航，不能成为高于原记录的真值。[实体抽取与合并](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_op.py)

引用仅通过提示词让回答写视频名和片段起止时间；不是原子 claim → 已访问 observation ID 的程序校验。ASR 算法版保存片段内部句级时间，Vimo 只拼接句子文本、丢弃返回时间；两者都没有当前项目依赖的词级剪辑边界。这使它可用于寻找约 30 秒候选，不能直接承担精确选段。[回答引用提示词](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/prompt.py)、[算法 ASR](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_videoutil/asr.py)、[Vimo ASR](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/python_backend/videorag/_videoutil/asr.py#L30)

VLM 输入包含转写，因此其 caption 可能混合“看到的内容”和“听到的内容”。采样也可能遗漏快速事件。生产记录仍应分开保存来源、采样时间和推断；用原转写支持“说了什么”，用画面记录支持“看到了什么”。这是针对现有项目的设计建议，不是论文已经保证的能力。

## 4. 部署、语言与依赖难度

算法 README 推荐单张 RTX 3090／24 GB，列出 Python 3.11、Torch 2.1.2、bitsandbytes、CTranslate2、MoviePy、ImageBind 等依赖，并下载 MiniCPM、Distil Whisper、ImageBind checkpoint。算法的向量存储查询直接 `.cuda()`，所以当前代码不能原样在 Apple Silicon 上运行；需要更换模型接入或硬件。README 仅声明在英文环境测试，caption 提示词固定要求英语；Distil Whisper 官方模型为英语模型。[算法安装与语言说明](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/README.md)、[CUDA 代码](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_storage/vdb_nanovectordb.py#L93)、[Distil Whisper 官方模型卡](https://huggingface.co/distil-whisper/distil-large-v3)

Vimo 使用云 ASR／VLM 后移除相关本地模型需求，并在 ASR 加 zh/en/ja 提示，README 声称已在 M1 Mac 验证。但 ImageBind 仍在本地，Mac 路径明确回退 CPU；英语 caption、图谱和多阶段查询依然存在。M1 能运行不表示当前项目的 512 MiB Agent 容器能承载它，也不能推出交互延迟。[Vimo 安装说明](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/README.md)、[设备代码](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/python_backend/videorag/_utils.py#L223)

实现难度的主要来源不是“调用模型”，而是增加本地模型运行时、另一套图／向量／文件状态，再将它接入现有分析发布、来源身份和任务恢复体系。云版降低本地硬件要求，但增加按片段计费的 VLM 调用；并没有消除工程整合工作。

## 5. 许可证：不能概括为整个项目 MIT

**源码观察：** 当前顶层和两个组件的 LICENSE 均为项目自定义 dual license，区分架构 MIT 与集成第三方模型的当前实现；说明完整现状实现受非商业限制，替换模型后需审查替代模型条款。复用架构代码也需要保留相应版权／许可信息，不能把上游声明推广到依赖权重。[当前 LICENSE](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/LICENSE)

| 依赖 | 本次可核实的许可信息 | 使用时的结论 |
|---|---|---|
| ImageBind | 官方 README 写 CC-BY-NC 4.0；VideoRAG 固定依赖提交的 LICENSE 实际标题为 CC-BY-NC-SA 4.0 | 两处均明确非商业，SA 存在上游说明不一致；应保留并核对实际使用提交／checkpoint 条款，不能声称商业可用 |
| MiniCPM-V 2.6 | 官方区分代码 Apache-2.0 与权重 MiniCPM Model License；模型卡说明商业使用需注册 | 不应仅凭 VideoRAG 的“MiniCPM Apache2”概括权重许可 |
| 云模型 | API 服务受供应商服务条款约束 | 换成云调用不使其它复制代码或 ImageBind 许可失效 |

来源：[ImageBind 官方 README](https://github.com/facebookresearch/ImageBind#license)、[VideoRAG 固定 ImageBind 提交 LICENSE](https://github.com/facebookresearch/ImageBind/blob/3fcf5c9039de97f6ff5528ee4a9dce903c5979b3/LICENSE)、[MiniCPM-V 官方模型卡 License](https://huggingface.co/openbmb/MiniCPM-V-2_6#license)。

## 6. 成本与延迟：能算工作量，不能替作者编实测

### 离线索引

设总视频长度为 `D` 秒，片段数 `N ≈ ceil(D / 30)`，文本 chunk 数 `C`。无缓存、无失败重试时：

- `N` 次 ASR 处理；算法本地、Vimo 云调用。
- `N` 次初始 VLM caption、约 `5N` 张输入帧。
- 约 `2C` 次实体／关系抽取 LLM：首次抽取＋默认一次补充；较长实体或关系描述另外摘要。
- chunk 与 entity embedding，以及 `N` 个 ImageBind 视频向量。
- 音频提取、临时视频重编码、解码、存储与模型加载。

例如整整 1 小时视频约 120 段：120 次初始 caption、约 600 帧。**这是默认参数推导，不是每小时价格；`C` 取决于实际生成文本长度。** 图谱是相对已有 ASR＋VLM＋混合检索新增的成本。[默认值与索引入口](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/videorag.py)、[默认补充抽取循环](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_op.py)

### 在线查询

设两路合并有 `R` 个候选、过滤后保留 `F` 个。普通问答冷路径大致执行：

- `R + 4` 次文本 LLM：实体改写、视觉改写、`R` 次过滤、关键词、最终回答。
- 2 次文本 embedding，以及一次 ImageBind 查询编码。
- `F` 次详细 caption、`15F` 张重新抽取的帧；不重复 ASR。

算法版对 `F` 个片段串行 caption；Vimo 的云 caption 使用 async，默认并发上限为 3。查询重新 caption 是固定路径，不是只有旧记录不够时才触发。文本 LLM 有结果 cache；VLM caption 函数没有对应结果 cache，重复提问仍重新解码与描述。算法版每次视觉查询还重新实例化 ImageBind；Vimo 用共享服务减少这一开销。[算法视频存储](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_storage/vdb_nanovectordb.py#L128)、[算法 caption](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/VideoRAG-algorithm/videorag/_videoutil/caption.py)、[Vimo caption](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/python_backend/videorag/_videoutil/caption.py)、[Vimo LLM cache 和 caption 配置](https://github.com/HKUDS/VideoRAG/blob/c412a093a820ef7a0e0dda31076ed871136198b3/Vimo-desktop/python_backend/videorag/_llm.py)

查询总体延迟包含顺序依赖的改写、召回、过滤、关键词、caption、最终生成；并发只减少部分阶段的墙钟时间，不能消除所有阶段。若整个 `.query()` 又被当前 LangGraph 当作一个工具调用，外面还存在 Agent 模型请求和治理成本。

**未发现的实测：** 本次审查的论文、算法／Vimo README 与实现没有给出可复现的“每视频小时美元成本”、逐查询端到端 p50／p95、建库实时率、Apple Silicon 实测吞吐。24 GB／RTX 3090 是作者的可运行容量说明，不是速度或低成本承诺。不能据此写“几秒回答”或“每小时几元”。

要决定增加哪条通道，应在本项目中文样本上分别记录：索引费用／视频小时、首次和缓存查询 p50／p95、VLM 补查次数、目标片段召回率、答复支持率、时间定位误差。分别做“现有混合检索”“加局部补查”“再加视觉向量”“再加实体图谱”的消融，只有可重复收益才支付额外复杂度。

## 7. 当前项目兼容性审查

审查本项目提交：`e65954da804b5ceab66e0d7e0be3493000847b30`。以下以代码为准，不把设计文档中的目标能力自动视作已经实现。定位来自 `docs/requirements.md`：个人中文访谈、课程、讲解视频的选段，多素材基础编排，以及内容问答；优先在本机运行，重视可核查出处、可执行时间线、恢复和调用成本。README 中的 16 GiB Apple Silicon、Docker 约 7.65 GiB 是 2026-10-10 的历史资源记录，本次未重新测量宿主资源。

| 维度 | 当前项目实现 | 上游实现与适配判断 |
| --- | --- | --- |
| 模型执行 | 云 Qwen ASR、VLM、Embedding；云 DeepSeek 对话；Python 容器限制 512 MiB | 算法路径需要本地 MiniCPM、Whisper、ImageBind/CUDA；Vimo 改用云 ASR/VLM，但仍保留本地 ImageBind。原封不动接入与部署基线冲突 |
| 中文检索 | Qwen text-embedding-v4 1024维，Qdrant multilingual BM25 + LITERAL + RRF | 原算法仅声明英语测试、英文 caption；中文适配需重评，不能拿论文分数作为本项目效果 |
| 存储 | PostgreSQL 证据、检索单元、引用、分析/索引代次；Qdrant 可重建索引；S3素材 | 默认 JSON/NanoVectorDB/NetworkX 与本项目权限、发布、清理机制不相容；不必为借鉴思路引入 Neo4j |
| 时间精度 | ASR句/词时间、派生音频偏移映射、帧实际PTS、毫秒半开区间 | 上游30秒段主要支持候选定位，算法ASR有段内转写时间但无本项目的词级业务契约；Vimo ASR只拼接文本。不能将30秒检索段直接当最终切点 |
| 输出 | 可验证的 evidenceId、已读回执、版本、剪辑时间线和渲染产物 | VideoRAG主要返回回答文本及可选视频名/区间引用，不直接提供本项目可发布的剪辑结果 |
| 工具控制 | LangGraph、素材范围、租约、回执、取消、费用不明确停止重做 | 若黑盒调用query，内部多次模型和VLM调用将绕过现有计数/回执；需拆分或替换执行适配，不能只包一个工具就视为治理完成 |

源码依据：[EvidenceSearch.java](../../backend/business-api/src/main/java/com/agenticvideo/business/search/EvidenceSearch.java)、[SearchVectors.java](../../backend/business-api/src/main/java/com/agenticvideo/business/search/SearchVectors.java)、[SpeechAnalysis.java](../../backend/business-api/src/main/java/com/agenticvideo/business/analysis/SpeechAnalysis.java)、[RepresentativeFrames.java](../../backend/media-worker/src/main/java/com/agenticvideo/media/RepresentativeFrames.java)、[vision.py](../../agent-service/agent_service/vision.py)、[conversation.py](../../agent-service/agent_service/conversation.py)、[compose.yaml](../../compose.yaml)。

当前已有局部视觉复查模块 `VisualInspections`，默认每个复查任务4帧、4次调用、180秒；`conversation.py` 的工具列表尚未接入复查。需求文档要求项目授权及每制作需求跨消息累计4次新增视觉调用，这是目标规则，不能把现有每复查任务限制当作已完成该共享预算实现。

还有一项必须处理的调度约束：`app.py:consume()` 在 `active` 非空时停止领取 `analysis.v1` 中的新任务。若对话占住唯一执行槽并等待同队列的 `INSPECTION_ANALYZE`，补查不会获得执行机会。设计应在创建补查并持久化依赖后 checkpoint 暂停、释放执行槽，分析结束后由任务系统恢复对话。该自动等待要与用户澄清等待区分；不是简单增加轮询或延长超时。另一选择是实现复查的受控同步执行适配，但同样要复用回执、取消与预算。现有框架不因此需要替换。

## 8. 第一性原理：真正必须解决的四件事

1. **可发现**：相关内容能够被找到。失败可能在采样、ASR/VLM、文本表示、检索或排序，不能都归为向量库问题。
2. **可定位**：每条观察回到明确的素材与实际时间。检索窗口是导航范围，最终切点另行核查。
3. **可核查**：回答或选段理由由正确来源支持。语音提及不证明画面出现，采样未命中不证明全片不存在。
4. **可承担**：基础分析可复用，补查有预算，简单查询不强制走昂贵视觉链路。

从这四点不能推出必须有实体关系图、直接视频向量、多个Agent或全量在线重看。每新增一项必须对应基线上的具体失败及可测收益。

## 9. 建议采用的最小方案

保留现有 ASR/VLM/Qwen文本Embedding/Qdrant混合检索/PostgreSQL证据/Java媒体模块/LangGraph。借鉴 VideoRAG 的分段定位和粗查后细看，暂缓图谱和直接视频向量。

### 9.1 离线处理

- 保留完整音频的句/词转写及音画时间映射，不为了RAG额外生成一整套30秒物理视频文件。
- 原始语音、画面观察、可见文字继续分开保存。联合检索表示可以串联带来源标签的内容，但回答和剪辑依据回到各自的原始记录；邻近转写不能作为视觉事实。
- 将当前一句一个检索单元扩展为有限的完整语句窗口。先采用确定性分组和相邻读取，通过样本决定窗口长度，不立即用LLM做所有语义分段。
- 提供按时间排列的内容目录。第一版可使用完整转写的分页概览和抽取片段；若全片主题浏览不足，再生成带原始引用的章节概要并缓存。当前 `SpeechAnalysis` 的前约500字符提要不构成全片总结。
- 保留稀疏视觉观察。当前基础每素材最多25帧，在60分钟视频上大致每150秒一个观察，短动作漏检是采样缺口。可按实际视觉任务评测覆盖或镜头变化采样；4帧局部补查不保证首次召回或全片覆盖。

### 9.2 Agent Interface

| 工具 | 作用 | 数据性质 |
| --- | --- | --- |
| `get_video_overview(assetId, cursor)` | 浏览内容分布、处理状态、覆盖；支持长视频分页 | 目录用于导航，不作唯一事实依据 |
| `search_evidence(query, sourceKinds, ...)` | 复用当前 dense/BM25/LITERAL/RRF，候选去重和必要上下文扩展 | 命中记录带 evidenceId/assetId/analysisId/时间 |
| `read_video_range(assetId, startMs, endMs, ...)` | 在同一原素材范围联合读取语音、帧观察和可见文字，限制窗口与返回量 | 各来源保持独立，保留词级时间与采样时刻 |
| `inspect_video_range(assetId, startMs, endMs, question)` | 在缺少可回答的画面信息时申请受控复查，返回新观察或待完成任务身份 | 复查结果是模型观察，不证明所有未采样帧 |

尽量复用和扩展当前接口，旧 `read_evidence_context` 可保留为兼容入口。上述新工具尚未实施。用户、项目、固定素材范围和预算由业务注入，不由模型决定。

普通语音问题：Agent检索→阅读→回答，新增视觉调用0次。

视觉问题且证据充分：检索→核查观察→回答，新增视觉调用0次。

视觉问题且证据不足：在授权和共享预算内补查局部范围，保存新观察后恢复Agent；不能只因进入视觉路径就自动把所有候选重新15帧caption。

没有候选时：有限查询改写、另一来源通道或更广但受控的覆盖采样；不可只在不存在的命中附近细看，也不可用局部复查宣称全视频不存在目标。

### 9.3 复查与缓存

第一步复用当前单帧观察模块和调用记录，让Agent能够取得补查结果；其能力仍限于单帧可见内容。若步骤顺序/动作过程的评测失败，第二步再增加带实际时间标注的多帧联合VLM调用，保持观测与推断分离。不能从4帧推断动作全程或精准运动边界。

模型观察复用键至少包括素材hash、分析版本、实际帧身份或范围、采样规则、模型/参数、提示版本；问题导向描述另含规范化问题或检查目标。通用已存事实可复用，但不同问题的定向描述不应盲目复用。缓存不能替代项目权限校验。

跨消息/重试共享的需求预算在业务后端强制实施；次数上限不等于金额硬上限，还应记录实际输入、输出和耗时。暂停前后的租约、幂等和恢复应有真实集成验证。

### 9.4 暂缓项及增加条件

| 暂缓项 | 何时值得增加 |
| --- | --- |
| 实体关系知识图谱 | 有足够跨视频关系/多跳问题，且现有检索＋全片浏览仍遗漏关联；先验证简单标签/链接是否足够 |
| 直接视频/图像向量 | 视频实际有目标，但文本化描述丢失信息导致召回不足；先比较更合适采样/定向描述与向量支路的增益 |
| 必选reranker | 正确候选已经召回，但排序反复失败；不能用精排解决采样漏检 |
| 全候选在线重caption | 查询相关性的提升超过延迟/费用，并有明确可复用策略；不能对所有问题默认启用 |
| 独立图数据库或另一向量库 | 当前存储无法表达已被证明必要的能力；当前版本没有这个证据 |

## 10. 成本、延迟和接入工作量的可执行判断

### 10.1 成本模型

离线成本 = ASR费用 + 基础VLM费用 + 文本Embedding费用 +（若采用）图抽取/摘要LLM费用 + 视频编码/解码、存储和算力费用。

在线成本 = 查询Embedding费用 + 模型决策/回答费用 + 可选改写/精排费用 + 新增局部VLM费用。

调用费用按各实际请求的输入/输出用量和所选提供方当期价格求和；本地模型还要计GPU租金/硬件与常驻时间。源码中的并发减少部分等待，不减少总费用；缓存只在命中时减少费用。

以恰好60分钟视频及算法默认参数估算，120个30秒段对应600个初始caption输入帧、120次初始caption、120段ASR、120段视觉编码；图抽取另外约每文本chunk2次LLM，再加可能的实体/关系摘要与Embedding。这个数是工作量，不是美元费用或速度实测。当前基础最多25个帧观察约对应最多25次不同图像调用，输入帧量差约24倍，caption次数约4.8倍；模型、分辨率、batch和缓存不同，不能把该比值当作费用或延迟比值。

原算法冷查询候选R个、过滤后保留F个时，约4+R次文本LLM、2次文本Embedding、1次ImageBind查询编码、F次VLM caption且每段15帧。只有4个候选并都保留也约8次文本LLM+4次VLM及60帧。原算法VLM循环串行；Vimo改为并发，但仍有图/视觉检索、网络、解码和输入量开销。若再把query作为Agent工具，外层Agent调用会额外叠加。

未运行GPU推理或付费模型，本研究不能报告可靠人民币/视频小时、秒/查询或p95。论文QA分数、24GB显存声明均不能证明本项目低成本低延迟。建议在小样本上记录冷/热查询、模型常驻/加载、离线每视频小时费用、查询p50/p95、新增VLM数量和输入帧数。

### 10.2 工程代价估算

以下为熟悉本仓库的一名工程师的粗估，并非实测工时或交付承诺；不包含GPU采购、云配额等待和扩大产品能力。

| 路径 | 工作量估算 | 主要工作 |
| --- | --- | --- |
| 有24GB CUDA GPU的独立研究复现 | 约3–5工作日 | 环境/模型准备，小样本跑通与记录；不等于生产接入 |
| 本项目借鉴粗查＋细查与浏览能力 | 约8–15工作日 | 基线评测、语句窗口/范围阅读、分页概览、复查工具、暂停恢复、预算和回执验证 |
| 完整或近完整VideoRAG能力迁入本项目 | 约20–40工作日 | 中文模型适配、视觉向量、图谱、数据迁移、来源映射、全部内部调用治理、部署与性能测试 |

在当前Mac/轻容器基线上原封不动运行算法并无可靠小工时承诺。Vimo的云模型迁移提供参考，但仍需替换/远程部署ImageBind及适配上述治理，不能把桌面程序启动成功等同于兼容。

## 11. 最小评测与决策门槛

第一轮3–5份代表素材、30–50个人工标注问题，包含中文讲话、屏幕文字和无音轨片段；这是诊断起点，不足以证明泛化或稳定p95。

问题分组：原句/姓名数字；同义主题；全片步骤/亮点浏览；只在画面出现；语音提及但画面未出现；短暂文字；事件前后关系；不存在/覆盖不足。人工标注支持答案的原素材时间和来源，不仅标答案字符串。

比较A当前基线、B仅加入时间窗口/浏览/局部复查；两者复用同一ASR/基础观察，分别记录补查新增量。先看错在哪：未采到、观察错、未召回、排序错、上下文不足、回答无依据。只有B仍存在大量文本化漏召回，才建立C视觉向量小试验；只有确有跨视频关系失败，才试图谱。

指标：正确来源的Recall@K、回答主张的证据支持比例、全片问题遗漏、事件顺序、时间定位误差、错误否定、成本、冷/热延迟、资源峰值。加入跨项目隔离、暂停恢复、取消及费用不明确不重做的回归。不得用论文的LLM相对打分替代剪辑范围和来源验证。

决策规则：在约定预算内B改善核心问题且不破坏现有治理，则合入；没有增益则停止扩张。C只为明确失败引入，并以相同素材和问题对比其净收益。不要为了声称采用VideoRAG而同时引入所有模块。

## 12. 结论

推荐把VideoRAG当作研究参考和评测对照，采用“可追溯文本混合检索＋全片浏览/时间范围阅读＋问题需要时局部视觉复查”，保持LangGraph为Agent运行框架。本项目已具备大半基础设施，最有价值的工作是补齐信息访问和受控补查，而非整体替换数据库/模型/业务层。

可以将项目亮点表述为：在有限采样和调用预算下，结合语音、画面与文字的独立来源，利用混合召回、时间上下文和定向补查提高可核查问答与剪辑定位，并用标注样本报告效果/开销取舍。该结论是设计建议，尚未执行实现或真实模型基准。

