# T10：用主题和近义表达检索视频内容

状态：已发布为 [#11](https://github.com/Hou1464851806/AgenticVideoProject/issues/11)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P1；标签为 `priority:P1` 和 `ready-for-agent`。

## What to build

用户无需记住原句，使用中文主题或近义表达也能查到带出处的片段，并保留专名与短语查找。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 真实验证 Qwen Embedding 模型与维度，以及固定 arm64 Qdrant 的原生中文 BM25；索引与查询分词一致，使用 RRF 融合并保留字面补充通道。
- [ ] 各通道召回前过滤权限、项目与有效代次，IDF 统计范围限定当前项目；不以召回后过滤代替授权。
- [ ] 索引新代次写入并核对单元、维度和可查询性后，由业务后端原子发布；未发布／取消／旧尝试数据不进入查询。
- [ ] 保存证据和向量批次便于恢复；用人工标注及保留查询比较稠密、BM25 与融合的 Recall@K／排序，不编造未经测量的质量阈值。

## Verification

真实模型与 Qdrant 上验证近义、专名、数字、跨项目负例及代次发布；记录三个通道的评测基线。

## Blocked by

- [#10](https://github.com/Hou1464851806/AgenticVideoProject/issues/10)：查找人名、数字和原句并跳转核查

## Requirement coverage

总需求用户故事：US-25, US-26, US-29, US-35。

总需求验收：AC-08, AC-12（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。

外部条件：实施者需具备对应提供方凭据与人工可核对素材。凭据缺失可完成独立编码／模拟验证，但必须保留真实联调未通过的状态。
