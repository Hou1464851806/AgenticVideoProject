# #11 中文主题、近义表达与混合检索

固定评审基线：`29f3ba9c5a471d987c9d713fde18f727e830bb4a`。测试沿用已确认的公开 HTTP、真实 PostgreSQL / Qdrant / 百炼模型及 Compose 浏览器。

## 实现与真实外部验证

- 北京百炼 `text-embedding-v4`，显式 `dimension=1024`；文档使用 `text_type=document`，查询使用 `query`。实际接口返回、保存及索引读取均验证维度。[百炼模型文档](https://help.aliyun.com/zh/model-studio/text-embedding-v4)
- `qdrant/qdrant:v1.19.2` 固定多架构 digest `sha256:b7b0444c4c351c970b98e90a6f89c2ee4287c65b44e52b4cb503fa5b2aa927ad`，Docker 实际运行 `aarch64`。arm64 manifest 为 `sha256:9eb80ba088703193c124db5e50a59f2028439c8bbc62fa1918625995116b50d0`。
- 原生 `qdrant/bm25`，`multilingual` 分词，禁用英文词干及停用词，索引和查询共享冻结配置；`k=1.2,b=0.75,avg_len=256` 沿用初始默认参数，尚未按真实语料调优。稀疏向量使用 IDF modifier。[原生中文分词配置](https://qdrant.tech/documentation/search/text-search/full-text-search/)
- 每次检索由 PostgreSQL 当前素材、源 SHA、分析 head 和已发布索引 head 生成 Qdrant 过滤；稠密及 BM25 都在召回前限制项目与代次。Agent 再收窄至固定素材范围；`params.idf.corpus` 保持当前项目全部有效代次，独立于更窄召回范围。[项目 IDF 接口](https://qdrant.tech/documentation/manage-data/multitenancy/)
- 三路排名用 RRF（常数 60）融合，不直接相加异质分数；字面模式仍做参数化原文包含查找，避免近似候选替代用户要求的原句。主题检索返回候选和证据，未设置未经评测的相关性分数阈值。
- 独立 `INDEX_BUILD` 任务及持久向量批次，失败不重跑 ASR。成功向量按分析、模型、维度、规则及单元身份复用；提交结果不明确的批次停止自动重复付费。取消和过期尝试沿用当前 attempt / lease 校验。
- Python 写入隔离代次；业务后端核对全部单元 ID、数量、归属、维度、实际向量与持久批次一致性、原生 BM25 及查询可用性，再在任务成功事务内原子切换索引 head。Qdrant 写入与数据库不假设分布式事务，迟到写入不能自行成为有效代次。
- HTTP `POST /assets/{id}/search-index` 单独重建，`GET` 查看代次；界面可选择原句或主题、查看索引状态、重建和回看原视频。Agent 受控工具复用相同检索模块及证据引用校验。

## TDD 证据

- 近义查询闭环：重建入口 404 → 真实 Qwen / Qdrant 查询通过，`/tmp/agenticvideo-issue11-red.log`、`green-first.log`。
- 界面：缺少检索方式 → 主题查询及独立索引状态通过，`ui-red.log`、`ui-green.log`。
- 向量恢复：重建批次错误回到 READY → 复用 SUCCEEDED，`recovery-red.log`、`recovery-green.log`。
- 原生索引发布：缺少状态入口 → 单元未完成拒绝发布、完成后幂等发布、取消的新代次不替换 head，`publication-red.log`、`publication-green.log`。
- 错误向量：同维度错误向量原先允许发布 → 与保存批次核对后拒绝，`vector-identity-red.log`、`vector-identity-green.log`。归一化浮点容差仅用于存储一致性，不是检索质量阈值。
- Agent：工具仍返回 LITERAL → 实际 Embedding / Qdrant 混合检索与引用链通过，`agent-red.log`、`agent-green.log`。该测试在外部 DeepSeek 接口使用确定性工具请求夹具；实际 DeepSeek 对话由完整浏览器套件另行覆盖。

## 人工标注与保留查询评测

语料为独立已知中文脚本的 5 个语音单元，人工按句意标注目标；3 条开发查询（姓名、数字、近义表达）和 3 条保留查询在测量前固定，未据此调整分词、权重或分数阈值。真实模型 / Qdrant 的逐查询排名、相关单元、Recall@1/3/5 和倒数排名见 [原始测量](issue-11-retrieval-baseline.json)。

| 查询集 | 通道 | Recall@1 | Recall@3 | Recall@5 | MRR |
| --- | --- | ---: | ---: | ---: | ---: |
| 开发 | Dense | 0.667 | 1.000 | 1.000 | 0.833 |
| 开发 | BM25 | 0.667 | 0.667 | 0.667 | 0.667 |
| 开发 | RRF | 0.667 | 1.000 | 1.000 | 0.833 |
| 保留 | Dense | 1.000 | 1.000 | 1.000 | 1.000 |
| 保留 | BM25 | 0.333 | 0.333 | 0.333 | 0.333 |
| 保留 | RRF | 0.667 | 1.000 | 1.000 | 0.833 |

融合没有在所有查询上改善排序：保留查询“制作完成后生成成片文件”在 Dense 第 1、RRF 第 2。人工目标“导出”对应实际转写，已知脚本的“短视频”被 ASR 漏掉；记录未隐藏上游遗漏。样本仅为合成语音小规模接口基线，不能推断自然长视频的生产准确率。跨项目空范围和其他账号请求没有泄露结果；无相关性阈值时，主题负例仍可能返回需人工核查的近似候选。

## 评审发现与修复

初次 Standards / Spec 均发现远程检索占用任务事务锁，可能阻塞取消或让过期结果发布。索引验证移至事务外，最终短事务用 `clock_timestamp()` 重新校验租约和业务 head；Agent 工具先持久预留 PENDING 回执与预算，外部调用后重新核查执行资格及证据 head。重复在途调用不自动重复付费。真实 Qdrant 延迟导致过期完成返回 409；Embedding 阻塞时公开取消在 1.5 秒内返回，迟到检索未产生正式答复，最终 CANCELLED。对应 lease-red / lease-green、tool-cancel-red / tool-cancel-green 日志。

全套初次运行暴露测试累积未完成上传和渲染预留，占用本机实际可用容量。测试通过自身账号公开取消并等待清理隔离场景；没有降低生产安全余量或绕过空间检查。定向 8 项通过，工具／方案相关 20 项通过。

两项独立复查均 PASS：Standards 0 项、Spec 0 项。

## 完整验证与评审

空间恢复后 `mvn -B -ntp verify`：63 项通过、0 失败／错误，BUILD SUCCESS（5 分 04 秒）。前端类型检查 / Vite 与三个 Compose 镜像构建均通过。浏览器初跑 51 通过 / 1 失败：旧缓存素材只有语音、索引尚未创建；测试通过公开重建入口准备后，原文检索 2 项定向通过（4.8 秒）。下一轮发现长期复用的预览项目已达到 10 个有效素材上限，另 51 项通过；缓存复用前经公开素材列表检查空间名额，满额则创建新的独立测试项目，保留旧素材和生产配额。预览定向 3 项通过（41.4 秒），最终完整浏览器 52 项全部通过（12.9 分钟）。两项独立复查均保持 PASS、0 项发现。
