# M1 完整收尾审查

日期：2026-10-15（Asia/Shanghai）。基准为 `49fd46b`，审查覆盖该 HEAD 之后完整工作区，包括原 Java 两模块移动到 `backend/src` 的实现、Python 接入、所有阶段证据及 README。差异以 `git diff --cached HEAD` 核对，移动文件同时对照原路径，避免将目录移动误判为全部重写。

结论：发现的候选上限问题与文档不一致均已修复，独立复核及最终回归通过，无未解决的阻塞发现。

规格源：[已确认最终设计](../java-tools-m1-design.md)；运行证据以[第五阶段记录](java-tools-m1-stage5.md)及其中原始 JSON 为准。代码审查分别使用 Standards 和 Spec 两个只读子代理，随后由主代理修复、运行验证并提交。静态审查本身不算运行验收。

## Standards

未发现需要修复的明确规范违规或阻塞提交的结构问题。仓库未提供额外 AGENTS/CONTRIBUTING 编码规范；核查依照最终设计的高内聚、低耦合及避免过度设计约束。

MQ 不承载分析业务规则，媒体层不维护任务状态，Python 凭据停留在传输层，工具入口复用搜索业务。旧 TaskExecution 大类与少量固定分派未作为本轮增加框架或包装层的理由。保留的 legacy Java 业务夹具及默认停用边界已在后端 README 和阶段记录说明。

结论：0 项阻塞性 Standards 发现。

## Spec

发现并修复 1 项 P2 契约问题：`QuestionEvidence.rank` 原先对每个索引/就绪来源分组各取 50 条，却未限制整个通道。两个分组各 30 条时，实际可向融合传入 60 条，返回仍是 `channel_limit=50/candidates_truncated=false`，与后端 README、第三阶段记录及设计的有界候选/可见截断要求不一致。

修复在每个通道的分组结果汇总之后、RRF 融合之前，按组内排名及稳定单元 ID 确定边界，只保留 50 条，并在累计超限时明确标记截断。未修改来源权限、每组召回过滤、付费提交或回执身份。

新增 `ReadToolsIntegrationTest.candidateLimitAppliesAcrossAllIndexGroupsBeforeFusion`，使用真实 PostgreSQL/Qdrant 与受控 Embedding HTTP：两索引分组各 30 个单元，分别隔离 DENSE/BM25，检查持久排名 50 条、截断可见、同调用重放一致及跨组查询向量只提交一次。修复前实际运行失败（预期 truncated=true，实际 false），修复后工具/JSON 专项 11/11 通过。Spec 子代理复核修复和测试后未发现其他阻塞问题。

结论：1 项 Spec 发现，已修复；无未解决的阻塞发现。

## 设计到实现与证据

| 最终设计验收项 | 实现及核查结果 | 运行证据 |
| --- | --- | --- |
| 1. 真实上传、Java 基础分析、四工具与真实标记 | `media/analysis/bailian/messaging/tools`；Python `JavaToolExecutor`。真实素材、来源回执、最终回答与 trace 已关联 | 第五阶段四视频新上传、四工具/DeepSeek/trace JSON |
| 2. 分页、UTF-8、来源、区间/观察点、覆盖 | `ReadTools`、`QuestionEvidence`、Python evidence 校验。范围读取走权威证据，导航不作为引用；本次补齐多组候选上限 | Java 读取工具契约、Python 跨语言契约、固定 VFR 人工标签 |
| 3. 独立来源就绪与不可用能力 | 来源状态冻结，读取不触发分析；Python 真实目录只开放四工具，旧浏览器 Agent 不接纳任务 | 受控来源/权限用例与真实“猫”样本；旧入口冒烟 |
| 4. 问答、补答与恢复的原快照 | `QuestionScopes`、Python runner/request。依赖失效明确停止，普通后续消息新建快照 | Java 冻结/换 head 用例、Python 行为测试及新素材跨进程恢复 JSON |
| 5. 回执、冲突、排名分页及费用恢复 | `question_tool_receipts/question_searches` 与持久模型回执；成功分析/向量复用，不确定停止重付 | 读取工具、分析、回执故障专项；本次多分组重放断言 |
| 6. 通道降级、取消与执行代次 | DENSE/BM25/LITERAL 独立状态；本机代次与取消核对 | Qdrant/Embedding 受控故障、取消及迟到发布用例 |
| 7. 保留已有媒体行为 | 单体 FFmpeg，冻结版本/确认/产物；无新增 Agent 制作链路 | 第五阶段 11 个浏览器用例及复验、MP4 探测/解码/波形、字幕截图 |
| 8. 分开报告验证类型 | 普通 Maven 不付费，门控跳过不算通过；脚本/真实模型结果分开保存 | 阶段一至五记录及本次最终回归清单 |
| 9. 实际 MQ 可靠性与并发 | 持久意图、publisher confirm、结果持久后 ACK、原子占用、有限重试与死信 | broker 故障、ACK 前实际断连重投、SIGKILL、积压时媒体峰值 1 |

README 操作入口已与 Maven/Compose/CLI 实际路径核对。另修正根 README 链接的 `docs/current-features.md`：原清单将重构前的浏览器 Agent 生成/修改/自然语言制作描述为当前能力；现明确列出当前 M1 边界，并将原内容标为历史交付。文档导航同步当前状态，根 README 也补齐了完整 Maven 验证所需的宿主 FFmpeg/ffprobe 前置条件。未删除历史交付事实，也未将其重新算成本次验收。

## 最终验证与未验证边界

逐类计数和审查结果见[结构化清单](java-tools-m1-closeout.json)。

- 工具/JSON 专项：11/11 通过，包含新增多分组上限回归；修复前失败、修复后通过。
- 修复后的完整 `mvn -B -ntp verify`：153项，149通过、4付费门控跳过、0失败/错误，构建与打包成功（02:04:56，Asia/Shanghai）。Python 67/67、0跳过；前端类型检查及生产构建通过。Compose配置、操作文档链接、diff空白检查及提交内容的实际配置凭据缺席检查通过。
- 既有真实付费模型、媒体/浏览器与 SIGKILL 结果保持第五阶段原运行记录；本次上限修复没有再次调用真实付费模型，也未重复运行这些浏览器/媒体场景。固定小样本均低于本次修复的通道上限，受影响的超限边界由真实基础设施集成测试验证。
- 未运行项目继续按第五阶段列明：Maven 付费门控类跳过、真实付费提交中途破坏、人工听音、多实例/集群/吞吐及稀疏采样之外的完整视觉覆盖。旧 Worker/Agent 制作浏览器全套测试未运行且不是当前受支持入口。

提交包含完整 M1 实施、阶段验收、清理及本次审查修复；测试结果与未验证项分别记录。
