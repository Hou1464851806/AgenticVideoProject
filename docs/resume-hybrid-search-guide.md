# 简历第四条：混合检索与融合的设计与实现

本文根据 2026 年 10 月 27 日核对的项目代码，说明视频证据怎样成为可搜索的数据，以及搜索结果怎样成为回答的引用。重点沿着 Agent 使用 search_evidence 工具的路径讲解。

先分清三个问题：检索单元如何构建，索引如何建立，用户提问后如何检索。前两项通常在视频分析后的后台任务中完成，第三项才发生在问答过程中。

## 一、先快速总结

面试时可以先这样回答：

> 视频分析后，我把语音转写、画面描述和画面文字保存成带来源和时间信息的证据，再建立用于搜索的检索单元。当前语音按 ASR 返回的句子构建，视觉按每条画面描述和可见文字构建，每个检索单元关联对应的原始证据。
>
> 后台索引任务固定要处理的单元及模型配置，分批生成 Embedding 向量，再向 Qdrant 写入稠密向量、BM25 数据和业务归属信息。写入后回读核对，通过后才在数据库中发布这一代索引。
>
> 用户提问时，在固定的素材和分析版本范围内进行向量检索、BM25 检索，并补充原文包含匹配，用 RRF 根据各路排名合并结果。命中后回查数据库中的原始证据，返回原文、来源和时间位置，并保存读取回执。最终回答只能引用本次执行实际读过、且属于允许范围的证据；不足时要求说明缺口。

简历写的是向量与 BM25 双路召回，这是主要检索方式。当前 Agent 检索代码还加入了 LITERAL 原文包含匹配，因此实际融合有三路。

完整流程如下：

```text
视频分析得到模型结果
    ↓
保存原始证据、检索单元和关联关系
    ↓
创建索引任务，生成向量并写入 Qdrant
    ↓
核对并发布索引

用户提问
    ↓
确定允许读取的素材、分析版本和索引代次
    ↓
语义检索 + BM25 + 原文包含匹配
    ↓
RRF 合并排名
    ↓
读取实际证据，返回原文和时间信息，保存读取回执
    ↓
Agent 生成答案，服务端检查引用后发布
```

## 二、先分清模型结果、原始证据和检索单元

### 2.1 模型结果还不是可以直接引用的一条证据

ASR 是把音频转成文字的语音识别模型。一次完整响应可能包含整段音频的多个句子、每句话的时间和字词时间。

VLM 是理解图片的视觉语言模型。一个画面观察可能包含画面描述，以及多条画面上的文字。

这些完整结果会先保存在模型调用记录中。业务发布时再解析成可以单独定位和引用的证据，保存在 evidence_segments 表。

例如，完整转写中有一句“使用 X200 前，需要预热三分钟”，系统为它保存一条证据，记录文本、视频时间、所属素材、分析版本和模型调用来源。

这里的“原始证据”指保存下来的转写或观察，不表示它一定正确。ASR 可能听错，VLM 也可能看错。保留来源是为了能够核查，不是消除模型错误。

### 2.2 检索单元是供搜索使用的文本记录

retrieval_units 保存检索单元。后续生成向量和建立 BM25 索引，使用的是这些单元的 text 字段。

原始证据保存更完整的观察信息；检索单元保存用于搜索的文本、时间和归属。两者通过 unit_evidence_refs 关联。

```text
模型完整响应
    ↓ 解析和校验
原始证据 evidence_segments
    ↓ 同时建立用于搜索的记录
检索单元 retrieval_units
    ↓ 通过 unit_evidence_refs 保留来源关系
搜索命中后能够回到原始证据
```

## 三、检索单元如何从证据构建

### 3.1 语音按 ASR 返回的句子构建

SpeechAnalysis.publish 遍历 ASR 返回的 sentences。对每句话读取文本、开始时间、结束时间和字词信息，检查句子时间有效、字词时间没有超出句子范围。

ASR 时间相对于提取出的音频，而提取音频的起点不一定与原视频起点一致。因此代码加上保存的时间偏移，并把结果限制在原视频有效时长内。

然后为这句话创建一条原始证据、一条检索单元，再插入一条关联记录。这些操作与分析任务的业务完成处于同一个数据库事务中。

下面是示意数据，实际 ID 使用 UUID，也就是用于区分记录的唯一编号：

```text
原始证据 evidence-1
  text = 使用 X200 前，需要预热三分钟。
  source_kind = SPEECH
  start_ms = 12000
  end_ms = 16000
  observation_json = 这句话的原始 JSON，包含字词信息
  model_call_id = 对应 ASR 调用记录

检索单元 unit-1
  text = 使用 X200 前，需要预热三分钟。
  source_kind = SPEECH
  start_ms = 12000
  end_ms = 16000
  rules_hash = sentence-v1

关联记录
  unit_id = unit-1
  evidence_id = evidence-1
  ordinal = 0
```

当前代码直接使用 ASR 的句子文本，不再调用模型改写，不另外运行分句算法，也没有按固定 Token 数切块或设置重叠窗口。Token 是模型处理文本时使用的基本单位；当前这段构建逻辑不是按它切分。

### 3.2 画面描述和每条可见文字分别构建

假设视频第 20 秒的 VLM 结果是：

```json
{
  "description": "操作人员按下设备的启动按钮。",
  "visibleText": ["X200", "温度 180℃"]
}
```

代码会构建三条证据和三个检索单元：画面描述一条，X200 一条，温度文字一条。画面描述使用 VISUAL 来源，可见文字使用 VISIBLE_TEXT 来源。空描述不会创建对应记录。

这些记录的 start_ms 和 end_ms 都是 20000，表示第 20 秒这一帧上的观察，不表示某个事件持续了一段时间。规则标识为 frame-observation-v1。

不能因为一帧出现了某个物体，就把这一观察解释为它在前后整个时间区间里都存在。

### 3.3 当前是一对一关联，没有合并多个证据块

当前构建代码中，一条语音句子、一条画面描述或一条画面文字，各自对应一个检索单元和一条证据。没有把相邻几句拼成大段，也没有把同一时刻的语音和画面描述合成一个跨模态段落。

关联表可以表达一个单元对应多条证据，但当前写入路径只插入一条关联，ordinal 为 0。表结构能表达某种关系，不等于业务已经实现了相应功能。

对应代码：[SpeechAnalysis.java](../backend/src/main/java/com/agenticvideo/business/analysis/SpeechAnalysis.java)、[VisualAnalysis.java](../backend/src/main/java/com/agenticvideo/business/analysis/VisualAnalysis.java)。

## 四、检索单元的数据模型

### 4.1 retrieval_units：检索单元本身

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| id | UUID | 检索单元 ID |
| project_id | UUID | 所属项目 |
| asset_id | UUID | 所属视频素材 |
| analysis_id | UUID | 来自哪个分析版本 |
| source_kind | VARCHAR(32) | SPEECH、VISUAL 或 VISIBLE_TEXT |
| text | TEXT | 用于搜索和生成向量的文本 |
| start_ms | BIGINT | 原视频时间，单位毫秒；语音为开始时间 |
| end_ms | BIGINT | 语音结束时间；画面观察与 start_ms 相等 |
| rules_hash | TEXT | 当前构建规则标识 |

analysis_id 用于区分同一素材不同分析版本的数据。rules_hash 当前保存 sentence-v1 或 frame-observation-v1 这样的固定字符串，不是现场计算的文本哈希，也不是文件 SHA-256。

这张表没有保存向量的字段。Embedding 结果先保存在索引批次记录中，再写入 Qdrant。

### 4.2 unit_evidence_refs：检索单元与证据的关联

| 字段 | 含义 |
| --- | --- |
| unit_id | 哪个检索单元 |
| evidence_id | 对应哪条原始证据 |
| ordinal | 证据在该单元中的顺序 |

主键是 unit_id 和 ordinal 的组合，同一个 unit_id 与 evidence_id 的组合也不能重复。它允许一个检索单元有多条有顺序的来源记录；当前实际构建是一对一。

### 4.3 为什么与 evidence_segments 分开

evidence_segments 除了文本、时间和归属，还保存原始 observation_json、语音的原音频时间和 ASR 调用来源，或者视觉的 frame_id 和 vision_call_id。

检索单元负责组织搜索文本，证据负责保留观察来源。搜索先对单元排名，再回到证据取得实际内容。

以当前一对一构建方式，直接使用证据表也可以做出更简单的实现。拆表不是完成检索的绝对必要条件。当前设计把搜索文本与原始观察分开，代价是重复保存部分文本和时间，并多了一次关联查询。如果以后调整搜索文本组织规则，这种关联结构能保留来源，但不能把未来可能的合并、切块功能当成已经实现。

表结构见 [V10__speech_evidence.sql](../backend/src/main/resources/db/migration/V10__speech_evidence.sql) 和视觉扩展 [V32__frame_observations.sql](../backend/src/main/resources/db/migration/V32__frame_observations.sql)。

## 五、索引构建从创建后台任务开始

### 5.1 有检索单元之后，安排 INDEX_BUILD

语音或画面业务发布时，在事务中保存证据、检索单元和关联，然后调用 SearchIndexes.enqueue 安排索引任务。这里是保存任务，不是立刻在事务中调用模型。

服务端先检查素材和当前分析版本有效。如果该分析版本已经有配置相同的待执行或运行中索引任务，就返回已有任务；已有任务使用不同配置时会拒绝冲突的构建。没有合适任务时才新建。

不必等待语音和画面两个分支都完成。语音先完成，可以先索引已有语音；画面证据后到，再由后续构建补上。

### 5.2 固定本次输入和索引代次

创建任务时读取当前分析版本的检索单元，保存本次的单元 ID、文本和来源列表，同时固定模型、向量维度、BM25 配置和目标集合。

每次构建有一个 generationId，称为索引代次，可以理解为这批索引的版本编号。执行过程中使用固定输入，不会每处理一批就随意换一组文本。

| 标识 | 含义 |
| --- | --- |
| analysisId | 证据来自哪个分析版本 |
| generationId | 哪一次索引构建的结果 |
| taskId | 哪个后台任务负责本次构建 |
| attemptId | 任务当前这一次领取执行 |

恢复同一个构建任务可以产生新 attempt，不代表一定新建 generation。

### 5.3 先检查向量是否可复用，再分批

代码会检查相同分析版本、模型和维度下，是否有相同检索单元 ID 与文本的成功向量。有就复用，不再次付费。

这不是全系统按文本内容共享的缓存。如果模型或维度不兼容，或者单元身份和文本不匹配，就不能按这条规则直接复用。

不能复用的文本按每批最多 10 条组织。例如 25 条新文本分成 10、10、5 三批，保存在 embedding_batches 中。之前提交结果不确定的记录也不会因创建新代次被自动当成未调用过。

这一步会保存索引任务、search_index_generations、Embedding 批次和 Outbox 通知。事务提交后，Outbox 发布器向 analysis.v1 发送通知，Java 消费者领取后执行。

相关代码：[SearchIndexes.enqueue](../backend/src/main/java/com/agenticvideo/business/search/SearchIndexes.java)。

## 六、生成向量，写入 Qdrant，再正式发布

### 6.1 逐批调用 Embedding，并保存响应和结果

Embedding 是将文本变成一串数值的方法。默认模型为 text-embedding-v4，向量维度为 1024，也就是每条文本得到 1024 个数。这些数字整体表示文本特征，不是 1024 个关键词。

消费者逐批处理，流程是：

```text
短事务：记录准备提交状态和当前 attempt
    ↓
事务外：提交这一批文本，等待 Embedding 响应
    ↓
短事务：保存完整响应
    ↓
解析并检查向量数量、维度和数值
    ↓
短事务：保存向量和用量，标记这一批成功
```

每批成功后独立保存，不必等整个任务完成。恢复时成功的跳过，已有响应的继续解析，尚未提交的才调用；可能已提交但没有响应的停止自动重付。

所有批次成功后才进入 Qdrant 写入。这里同步调用模型，只表示当前消费线程等待一次请求返回；整个索引仍然是独立后台任务。

### 6.2 准备集合和过滤字段

Qdrant 是保存和查询检索索引的独立服务。collection 是其中容纳一组检索记录的集合。项目集合名称与模型、维度和 BM25 配置有关，不是每个视频单独建一个集合。

目标集合不存在时，代码创建 dense 稠密向量配置和 bm25 稀疏向量配置。dense 使用余弦相似度，可以先理解为比较两个向量方向有多接近。

还会为 projectId、generationId、assetId 和 sourceKind 建立便于筛选的字段索引。它们帮助限定搜索范围，与根据文本意思匹配的向量索引用途不同。

### 6.3 每个检索单元写成一个 point

point 是 Qdrant 中的一条记录，包含 ID、向量和附带业务信息。结构可简化为：

```text
point
├─ id：由 generationId 和 unitId 确定生成
├─ vector
│  ├─ dense：已生成并保存的 Embedding 数值向量
│  └─ bm25：文本和 BM25 处理配置
└─ payload
   ├─ projectId
   ├─ assetId
   ├─ analysisId
   ├─ generationId
   ├─ unitId
   └─ sourceKind
```

payload 是附带的业务字段，用于筛选范围、核对归属以及定位数据库里的检索单元。

dense 使用外部 Embedding 生成的向量；bm25 将文本交给 Qdrant 原生 BM25 能力处理，不是同一次 Embedding 调用生成两种向量。项目没有为 BM25 另行引入 Elasticsearch。

同一代次和单元使用稳定的 point ID，所以重新写入本代数据不会不断创建随机 ID 的重复记录。新代次有不同 ID，不直接覆盖旧代次。写入使用 wait=true，等待写操作完成。

### 6.4 回读检查实际写入结果

写入接口成功后，还要从 Qdrant 回读。代码检查本代数量、预期 ID、项目和代次归属、检索单元关联、向量维度与数值，以及 BM25 稀疏数据是否存在。

还会对照数据库已保存向量检查实际向量，并分别做一次稠密向量和 BM25 查询，确认本代数据基本可查询。

余弦集合会将向量归一化，也就是调整向量长度而保留方向。因此比较时先按相同规则换算，并允许小范围浮点误差。

这些检查确认数据完整、身份匹配和基本可查询，不是检索质量评测，不能据此宣称搜索准确率已经达到某个数值。

### 6.5 在数据库中发布索引

核对通过后，最终完成事务重新检查执行资格和当前分析版本，将索引代次设为 PUBLISHED，更新 search_index_heads 指向这一代，并完成索引阶段、任务和 attempt。事务提交后才 ACK。

search_index_heads 可以理解为“每个素材当前默认使用哪一代索引”的登记表。Qdrant 中物理上已有数据，与数据库认可这一代可以使用，是不同状态。

Qdrant 写入和回读都在 PostgreSQL 事务外。数据库事务无法撤销外部写入，因此采用先构建并核对、再发布的顺序。旧代次仍有效时，可以在新代次构建期间继续使用。已经固定范围的问答也不会因为默认指向变化而自动切换索引。

### 6.6 构建期间又有新证据怎么办

假设第一代固定了 20 条语音单元，构建期间又产生了 5 条画面单元。当前任务仍处理原先 20 条，完成时检查是否有新单元，必要时安排下一代，包含当前的 25 条。

下一代可以复用满足条件的成功向量，但会写入属于新代次的索引记录。不能把这描述为当前任务的输入不断变化。

如果向量都已保存，只是 Qdrant 写入失败，安全恢复时可以跳过模型调用，使用已保存向量重写本代数据，再核对和发布。付费提交结果未知则仍不能盲目重试。

对应代码：[AnalysisProcessing.index](../backend/src/main/java/com/agenticvideo/business/analysis/AnalysisProcessing.java)、[SearchIndexBuilder.java](../backend/src/main/java/com/agenticvideo/business/search/SearchIndexBuilder.java)、[SearchIndexes.java](../backend/src/main/java/com/agenticvideo/business/search/SearchIndexes.java)、[TaskExecution.java](../backend/src/main/java/com/agenticvideo/business/task/TaskExecution.java)。任务投递与恢复详见[第一条文档](resume-task-reliability-guide.md)。

## 七、用户搜索前，先固定允许读取的范围

Agent 使用 search_evidence 时，不是在全部历史数据中任意搜索。Java 为本次执行保存 scope，也就是允许读取的范围，包含素材、分析版本、已就绪来源、证据集合和索引代次。

固定范围后，同一执行的前后工具调用不会悄悄切换分析版本。新的证据即使后来准备好，也不自动加入原来的范围。必要依赖失效时，不能用旧回执掩盖问题。

工具参数可以选择素材和来源。来源包括 SPEECH、VISUAL、VISIBLE_TEXT，未指定时当前默认是 SPEECH。要问画面文字，需要选择正确来源，不能以为每次默认都搜全部来源。

代码检查请求素材是否在允许范围内，并在 Qdrant 查询中加入项目、代次和来源过滤条件。命中后还会回查数据库，确认单元确实属于本次范围。

对应代码：[QuestionScopes.java](../backend/src/main/java/com/agenticvideo/business/tools/QuestionScopes.java)、[ReadTools.java](../backend/src/main/java/com/agenticvideo/business/tools/ReadTools.java)。

## 八、三路检索各自解决什么问题

### 8.1 DENSE：根据意思接近程度找内容

用户问“机器开始工作前需要做什么准备”，原文是“使用 X200 前，需要预热三分钟”。两者用词不完全相同，但意思相关，向量检索适合补充这种匹配。

查询时先把搜索文本转换为向量，再与限定范围内的证据向量比较。查询必须使用与对应索引相容的模型和维度，不能随意换模型混用。

### 8.2 BM25：根据词语匹配排序

用户问“X200 要预热多久”，其中 X200 是具体型号。BM25 根据词语是否出现、出现频率、词在语料中的普遍程度和文本长度等因素排序。

IDF 表示一个词在语料中的区分作用：很多文本都有“设备”，只有少量文本有“X200”，后者通常更有助于缩小范围。查询中会把相应范围作为 BM25 的语料条件。

稀疏向量可以理解为：词表很大，但一条文本只涉及其中少量词，只记录相关词及其权重。稠密向量则是固定长度的一串数，整体表达文本特征。

不要把两者的分工说成绝对规则。向量也可能匹配型号，BM25 也可能漏掉关键词；混合使用是为了利用不同匹配信号，不是保证永远不会漏检。

### 8.3 LITERAL：检查完整查询字符串是否出现

这一条在 PostgreSQL 中检查检索单元 text 是否包含完整查询字符串。例如查询 X200，可以找到直接包含该字符串的文本。

它不是 BM25 分词检索，也不是自动把整句问题拆成多个关键词。用户查询一整句长问题时，如果原文没有完全相同的连续表达，就可能没有匹配。

当前命中按素材、时间和 ID 稳定排序，不是另外计算复杂相关性。它也参与后续 RRF 融合。

“三路”指三种检索方式，不表示代码一定启动三个并发线程。当前 Agent 检索路径按代码顺序执行各路及其配置分组。

## 九、RRF 怎样合并排名

召回是先找出可能相关的一批候选，不表示每个候选都能回答问题。各路原始分数含义不同，不能直接把向量相似度和 BM25 分数随意相加。

RRF 使用名次计算贡献，再相加。当前代码公式为：

```text
某一路贡献 = 1 / (60 + 这一路的排名)
最终分数 = 各路贡献之和
```

排名从 1 开始。某一路没有命中就不贡献分数。下面只用两路举例，实际 LITERAL 也参与同样的计算：

| 检索单元 | 向量排名 | BM25 排名 | 融合分数 |
| --- | --- | --- | --- |
| A | 1 | 3 | 1/61 + 1/63，约 0.0323 |
| B | 2 | 未命中 | 1/62，约 0.0161 |
| C | 未命中 | 1 | 1/61，约 0.0164 |

A 同时在两路靠前，因此融合后领先。代码按融合分数降序排列，同分时按 ID 稳定排序。这里由 Java 计算，没有再调用大模型逐条评分。

当前每路最多保留 50 个候选，融合后最多保留 100 个检索单元。多个索引配置分组存在时，代码分别查询，再按组内名次和稳定 ID 限定该路候选；不是直接比较不同配置的原始分数。超过数量限制会记录截断标记。

RRF 分数不是正确概率，也不证明一条内容足以支撑答案。它只用于候选排序。

核心实现：[QuestionEvidence.rank](../backend/src/main/java/com/agenticvideo/business/search/QuestionEvidence.java)。

## 十、命中后怎样返回实际证据

Qdrant 索引是由数据库数据构建的搜索数据。不能因为它返回一个 unitId，就直接认定这个单元属于本次用户和素材范围。

代码回查 PostgreSQL，检查单元在范围内，再通过 unit_evidence_refs 找到原始证据。最终读取证据原文、素材、分析版本、来源和时间信息。不同单元关联同一条证据时，返回时去重。

语音返回时间区间，画面观察返回 observed_at_ms 时间点。这样既能核查文本，也能定位回原视频相应位置。

相同 scope 和相同规范化查询条件的排名会保存下来。规范化包括将素材和来源列表整理为稳定顺序，便于判断是不是同一条件。后续相同搜索可以复用已保存结果，翻页沿固定排名继续，不每翻一页就重新生成查询向量。

如果查询已经登记，但结果没有确认保存，代码不会简单当成全新查询再次付费，而会返回尚未确认的错误。它不能保证任何查询中断都自动恢复成功。

工具返回还有分页和字节限制。候选数量限制和单页返回限制是两回事：先限定搜索候选，再按实际证据记录分页。原文不会为了塞进一页被随意截断；需要更多内容时，Agent 应继续翻页或按时间范围补读。

## 十一、如何保证回答引用的是实际读过的证据

### 11.1 工具返回记录时留下读取回执

回执就是持久保存的调用记录。search_evidence 或 read_video_range 返回原始记录后，Java 保存这次调用的请求与结果，记录实际返回过哪些证据。

这里的“读过”是指证据确实由工具返回给了本次执行，不是程序证明模型已经正确理解。

get_video_overview 返回的是导航信息，不能直接把导航 ID 当作已读原始证据。search_evidence 本身会返回原始证据记录，因此也不要求每次搜索后一定再调用 read_video_range；需要前后文时才继续补读。

### 11.2 提交答案时再次核对引用

Agent 输出答案正文和证据 ID。Python 先检查引用是否在已读观察中，Java 发布结果时再次核对：

1. 范围与当前执行一致。
2. 工具回执属于本次任务，已经成功且不是模拟结果。
3. 证据 ID 实际出现在回执返回的 records 中。
4. 证据属于允许范围和对应项目，必要依赖仍然有效。

因此，不能编造一个 ID，也不能只因为某条证据存在于数据库，就直接引用。当前执行必须有相应读取记录。

这些检查保证来源和读取关系，不证明答案每句话都被证据充分支持。不能将它表述为“彻底消除幻觉”。

对应代码：[ReadTools.java](../backend/src/main/java/com/agenticvideo/business/tools/ReadTools.java)、[AgentRunResults.java](../backend/src/main/java/com/agenticvideo/business/agentrun/AgentRunResults.java)、[result.py](../agent-service/agent_service/runtime/nodes/result.py)。

## 十二、检索失败、没有命中与证据不足要分别说明

向量查询失败，不等于没有相关内容。代码为各路返回状态，区分可用、部分不可用、不可用和未就绪。例如查询 Embedding 失败时，BM25 和原文匹配仍可能工作；Qdrant 不可用时，PostgreSQL 原文匹配仍可能工作。

降级就是保留仍可工作的部分，并告诉调用方缺少了哪些能力。不是隐藏错误，也不是把所有失败都当成空结果。这里说的是预期的检索依赖故障，不能保证任意数据库或程序错误都可降级。

证据不足时，提示词要求 Agent 补读或说明缺口。程序也对无引用回答、全片覆盖不足以及视觉问题仅引用语音等情况做检查。其中一些检查使用关键词和状态规则，能力有限，不是完整理解每句话的语义后作证明。

还要注意：候选数量有限，分页可能没有读完，画面只进行了稀疏抽帧。因此“没有搜索命中”不等于“视频中绝对不存在”。回答应准确描述已读范围，不能把局部观察扩大成全片结论。

对应提示词：[prompts.py](../agent-service/agent_service/prompts.py)。

## 十三、面试时可以怎样逐步展开

先使用第一节的快速总结说明全流程。追问时按数据构建、索引、搜索、引用这四步展开，不必开头就介绍所有表名。

**问：检索单元是怎么构建的？有没有切块和重叠？**

> 当前构建比较直接，语音按 ASR 返回的句子，画面按每条描述和可见文字分别建立单元，没有额外做固定 Token 切块或重叠。每个单元保存文本、素材、分析版本、来源和原视频时间，并通过关联表指向证据。搜索对单元排名，最终返回和引用的是原始证据。关联表能表达一对多，但当前实际构建是一对一。

**问：索引是如何建立并发布的？**

> 分析发布证据时安排独立索引任务，固定检索单元和配置，按每批最多十条生成向量，每批结果单独保存。全部准备好后写入 Qdrant 的稠密向量和 BM25 数据，回读核对数量、身份、向量和基本查询能力，再在数据库事务中发布这一代索引并完成任务。外部模型调用和 Qdrant 操作不包在一个长期数据库事务里。

**问：为什么要把索引分代？失败后能复用吗？**

> 每次构建有独立代次，写入时带上代次标识，新一代通过核对后才更新默认使用的索引。这样不会把写了一半的数据直接当成可用索引。向量已保存而 Qdrant 写入失败时，可以跳过 Embedding，使用稳定的 point ID 重写本代数据。新代次也可以按相同分析、模型、维度和单元文本复用成功向量，但付费结果不确定时不会盲目重调。

**问：为什么同时使用向量和 BM25？**

> 用户可能用另一种表达提问，向量检索有助于找到意思相关的内容；型号、专有名词和数字等词语匹配则需要补充另一种信号。所以我结合向量和 BM25，并保留原文包含匹配。它们分别召回候选，再融合排名，不假定任何一路单独就能覆盖所有问题。

**问：RRF 为什么不直接加两路原始分数？**

> 向量相似度和 BM25 分数含义、范围不同，直接相加难以比较。RRF 按各路名次计算贡献，代码是 1 除以 60 加排名，再把各路贡献相加。同时在多路靠前的结果通常会得到更高分。这个分数只决定排序，不是事实正确概率，当前也没有额外调用大模型进行这一步排序。

**问：怎么防止搜到不属于当前素材的内容？**

> Java 先固定本次读取范围，包含素材、分析版本和索引代次。搜索参数必须在这个范围内，Qdrant 查询也带项目、代次和来源条件。返回的单元还会在 PostgreSQL 中核对，再读取范围内的原始证据。因此不是完全信任索引返回的 payload，也不会在同一执行里自动切换最新分析版本。

**问：怎样检查模型没有乱写引用？**

> 工具返回原始证据时保存读取回执，结果发布时检查引用确实出现在本次任务的成功回执里，而且属于允许范围。Python 也检查引用是否来自已读观察。这样能阻止编造或越界引用，但不能证明回答每句话都准确，因此证据不足时还要补读或明确说明缺口。

**问：检索不到，能回答视频里没有吗？**

> 不能直接这样判断。先看各路是否正常、来源是否就绪、候选有没有截断、分页是否读完，再考虑画面稀疏采样的限制。查询模型失败可以保留关键词和原文检索，但要说明能力缺失。没有命中只说明在这次查询条件和读取范围内没有找到，不能自动推出全片不存在。

## 十四、源码阅读顺序

建议始终使用一个例子：“视频里说使用 X200 前预热三分钟，画面显示温度 180℃，用户询问 X200 的准备步骤。”先跟数据产生，再跟索引和查询，最后检查引用。

| 顺序 | 文件 | 重点看什么 |
| --- | --- | --- |
| 1 | [V10__speech_evidence.sql](../backend/src/main/resources/db/migration/V10__speech_evidence.sql)、[V32__frame_observations.sql](../backend/src/main/resources/db/migration/V32__frame_observations.sql) | 证据、检索单元及关联表，语音区间和画面时间点的区别 |
| 2 | [SpeechAnalysis.java](../backend/src/main/java/com/agenticvideo/business/analysis/SpeechAnalysis.java) | publish 如何按句生成证据与单元，映射时间并安排索引 |
| 3 | [VisualAnalysis.java](../backend/src/main/java/com/agenticvideo/business/analysis/VisualAnalysis.java) | publish 和 save 如何分别处理描述与可见文字 |
| 4 | [SearchIndexes.java](../backend/src/main/java/com/agenticvideo/business/search/SearchIndexes.java) | 先看 enqueue：固定输入、创建代次、复用向量、组织批次与 Outbox |
| 5 | [AnalysisProcessing.java](../backend/src/main/java/com/agenticvideo/business/analysis/AnalysisProcessing.java) | index 如何逐批处理、恢复已保存响应并跳过成功批次 |
| 6 | [SearchIndexBuilder.java](../backend/src/main/java/com/agenticvideo/business/search/SearchIndexBuilder.java) | 集合、过滤字段、point 结构以及实际 Qdrant 写入 |
| 7 | [SearchIndexes.java](../backend/src/main/java/com/agenticvideo/business/search/SearchIndexes.java)、[TaskExecution.java](../backend/src/main/java/com/agenticvideo/business/task/TaskExecution.java) | 回到 verify、publish 和完成分支，区分外部核对与事务内发布，查看新增证据的后续安排 |
| 8 | [QuestionScopes.java](../backend/src/main/java/com/agenticvideo/business/tools/QuestionScopes.java) | 哪些素材、分析版本和索引代次进入固定范围，如何检查依赖 |
| 9 | [ReadTools.java](../backend/src/main/java/com/agenticvideo/business/tools/ReadTools.java) | search_evidence 参数检查、默认来源、分页以及工具回执 |
| 10 | [QuestionEvidence.java](../backend/src/main/java/com/agenticvideo/business/search/QuestionEvidence.java) | search、rank、searchRecords：保存排名、三路检索、RRF、范围核对和证据回查 |
| 11 | [SearchVectors.java](../backend/src/main/java/com/agenticvideo/business/search/SearchVectors.java) | 查询向量如何生成，BM25 参数和 Qdrant 过滤条件如何传递 |
| 12 | [result.py](../agent-service/agent_service/runtime/nodes/result.py)、[AgentRunResults.java](../backend/src/main/java/com/agenticvideo/business/agentrun/AgentRunResults.java) | Python 的来源检查与 Java 的持久回执校验，各自能保证什么 |
| 13 | [prompts.py](../agent-service/agent_service/prompts.py) | 如何要求补读、引用已读证据和说明覆盖不足 |

阅读后可以用三个问题自查：一条语音怎样变成可搜索记录；向量成功但 Qdrant 写入失败时怎样恢复；模型引用一个从未返回给它的证据 ID 时，哪一层会拒绝。能沿代码讲清这三个例子，就能把数据模型、索引流程和引用校验连起来。
