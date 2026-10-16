# 对话生命周期步骤 7：阶段审查、验收与本地部署

日期：2026-10-16（Asia/Shanghai）。实施起点 `64e65b9`，当前分支 `main`，起始工作区干净。阶段审查固定基准 `ac4b1fb`，覆盖第 1～6 步及本步增量。依据[完整设计](../conversation-lifecycle-browser-design.md)、[第 6 步交接](conversation-lifecycle-step6.md)和根 [AGENTS.md](../../AGENTS.md)。

## 本步修复

- `ExportArtifacts.read` 的三元表达式使用装箱零值，存储返回 `null` 时保持引用类型，最终公开 `storedBytes=0`，避免意外拆箱导致 500。不把外部已丢失文件计为本次删除释放空间。
- 导出 HTTP 集成测试对可用、已删除及外部丢失三种读取补 HTTP 200 和整数类型断言，避免错误响应因 `asLong()` 默认零而通过。
- 真实媒体浏览器验收创建正式导出后从隔离对象存储删除文件，验证查询 200／0 字节、恢复记录、播放不可用反馈、明确删除释放 0 字节及刷新恢复。
- 设计审查发现真实模式提示词仍宣称“保存只能 CLI、预览导出未开放”；按 `request.public_context` 区分浏览器按钮与 CLI 操作指引，保留 CLI 用途。UNDERSTAND／DECIDE 两阶段均有最终提示词断言。
- 真实模型验收额外暴露并修复三项边界问题：模型将可选 question 返回字符串 `"null"`，现在拒绝该格式并走现有有限修正；严格时长的相同整数经 DB／Java 变成 IntNode／LongNode 后被误判不同，改用既有规范化条件哈希核对，实际值变化仍拒绝。另补理解边界：模型不能把不存在或已冻结的当前制作解析为 CONTINUE_PRODUCTION，非法关系按原预算修正，不自动另建制作。三处新增问题均先补公共入口红测试。
- README 与功能说明更新为当前自然语言聊天、草稿预览／确认及方案列表导出入口，历史能力不当作当前浏览器入口。

开发红→绿：先补 HTTP 断言复现 500，再修复；先补最终浏览器提示词测试复现 2 项失败，再修复。新增浏览器测试先后因遗漏“选择版本”、误将聊天既有预算失败提示也计入方案区错误而失败；只修正测试步骤及断言区域，失败不计通过。一次重复 Maven 调用在发现前一次尚未完全退出后立即中止，不计通过。后续 Maven 串行。

## 验收矩阵与证据入口

本矩阵将设计场景映射到本轮执行的公共边界测试，不以历史步骤的通过数代替当前回归。受控模型用于确定性故障、并发和事务；付费模型另列。

| 场景 | 当前验收接缝／证据 |
| --- | --- |
| CL-A01 问答及追问 | AgentRunsApiIntegrationTest 的快照、读取回执及证据归属；真实 Java 浏览器问答／原视频核查；Python conversation runtime |
| CL-A02 讨论 | prepare/execute 公共入口；Java 无制作发布讨论；真实浏览器 DISCUSS |
| CL-A03 问答转制作 | AgentRunsApiIntegrationTest（含严格时长同值通过、实际改值拒绝）、AgentWorkerIntegrationTest；真实浏览器双输出校验与未确认无版本 |
| CL-A04 指定输出修改 | Python test_real_production 与 conversation runtime；浏览器 A→B、历史只读；真实模型精确时长修改 |
| CL-A05 制作中插入问答 | Python query_after_draft；Java currentDraftSurvivesDiscussion；浏览器讨论后确认资格保持 |
| CL-A06 等待中独立问题及补答 | Python waiting_production／预算和故障归集；Java independentQuestion；真实 MQ Worker 等待释放及关联补答；浏览器引用问题 |
| CL-A07 未确认预览 | LocalMediaIntegrationTest 草稿→MQ→FFmpeg→存储→聊天播放，未写版本／确认；浏览器解码和时间推进 |
| CL-A08 历史预览 | Java completedHistoricalPreview／修改后禁止重渲染；浏览器 A 预览、B 当前、来源保持 |
| CL-A09 多输出 | 共享 hash／契约、Python 局部修改、Java 整体确认；浏览器选一条预览／导出 |
| CL-A10 幂等确认及回滚 | Java confirmationFailureRollsBackVersionAndConcurrentSameKeyReturnsOneResult；浏览器所见身份绑定与确认 |
| CL-A11 并发与过期草稿 | AgentRunsApiIntegrationTest 的编辑失效／迟到写入／同 key 并发、确认失败回滚 |
| CL-A12 冻结后继续及新制作 | Python frozen_production；Java 保存后仍讨论；浏览器冻结后讨论、新制作等待及新条件 |
| CL-A13 停止／失败不回滚 | Python failed_edit_then_query；Java editingInvalidatesOldDraft；浏览器新 60 秒条件和预算失败无重试 |
| CL-A14 独立停止及离页 | Java stopPreventsPublication；浏览器边界 Agent／媒体各自停止，真实浏览器刷新恢复 |
| CL-A15 重投／重启／迟到 | AgentWorkerIntegrationTest 真 MQ、Python 进程退出／回执恢复／ACK 丢失；Java 过期租约与媒体重投唯一发布 |
| CL-A16 失败重试／不确定 | Python UNKNOWN、预算；Java retryCannotForceUnknownPayment；浏览器原执行重试、UNKNOWN 无按钮 |
| CL-A17 快照／SSE／签名 | ConversationRecoveryApiIntegrationTest、浏览器窗口外草稿恢复、同产物续签、显式文件失效重试 |
| CL-A18 正式成片 | LocalMedia 真渲染、浏览器 720×1280 播放时间推进与下载；外部文件丢失查询及刷新恢复 |
| CL-A19 权限／迁移／双来源 | ConversationLifecycleSchemaIntegrationTest 完整迁移及约束；AgentRun／Preview／Export 跨项目拒绝、旧版本媒体路径 |

## 本轮检查结果

测试范围依据：本步先跑导出 HTTP 单行为及模块、提示词／生命周期直接模块、当前有效浏览器入口；阶段收尾按 AGENTS.md 执行 Java／Python 完整非付费回归、后端打包与前端构建。付费开关独立，历史退役浏览器套件不作为门槛。

| 检查 | 结果 |
| --- | --- |
| 导出模块 | ExportApiIntegrationTest 9 项通过，0 失败／错误／跳过 |
| 提示词及直接 Python 模块 | question 修复后提示词／生命周期／制作 41 项通过；最终关系修复后提示词／生命周期／Worker 35 项通过，均包含在最终完整回归中 |
| Python 完整非付费 | 最终 208 项通过；compileall 通过 |
| 前端 | vue-tsc／Vite 构建通过；HTTP 边界浏览器 5 项通过 |
| 真实 Java 媒体浏览器 | 全量中 LocalMedia 全部 6 项通过，包含增强后浏览器 1 项；最终 1080p 测试增量对应单项再次通过，0 失败／错误／跳过；浏览器 1 项通过（21.0 秒），实际 1080×1920 播放推进 |
| Java 完整非付费及后端包 | `verify` BUILD SUCCESS，35 类共 241 项：237 通过、0 失败、0 错误、4 项 JavaAnalysisLiveTest 按非付费开关跳过；后端 JAR 打包通过，耗时 14:31 |
| 真实模型浏览器 | 最终 1 项通过：4 轮真实输入全部成功，12 次 deepseek-flash 调用均有 RECEIVED 回执；2 秒→1.5 秒条件、规范化草稿与实际预览规格一致 |
| 最终差异、文档链接 | 5 份 Markdown、103 个本地链接有效，模型／部署 JSON 可解析；git diff --check 通过 |

全量运行时，在其浏览器用例已执行后增补了 1080p 真实导出／解码／播放断言；应用代码未再修改。待全量完整结束后，按 AGENTS.md 补跑 LocalMedia 对应单项通过；不将该单项报告冒充再次全量。完整 Surefire 报告与日志另存本地 `backend/target/conversation-lifecycle-step7-full-reports/` 及 `conversation-lifecycle-step7-full-build.log`。全量期间受控故障与部分旧上下文定时清理在隔离容器关闭后的连接异常按测试报告单独统计，不把日志错误本身写成验收通过。

## Standards

按 code-review 技能并行独立审查规范与设计。Standards 以 `ac4b1fb` 为基准，检查根 AGENTS.md、相关 README 及 Fowler smell 启发式。阶段实现与本步修复增量均无剩余已确认问题；静态审查不替代运行验收。

## Spec

同一基准独立核对 CL-Q1～Q13 和 CL-A01～A19。发现真实提示词错误引导 CLI 的 P2，已红测试复现、修复并独立复核。后续复核指出真实模型测试仅检查编辑代次不足以证明时长修改，已补条件及规范化时长精确断言。上述三项真实运行新增修复、严格时长条件篡改拒绝及验收断言增量也已独立复核，无剩余已确认问题。

## 真实模型与媒体边界

显式 `-Dlifecycle.browser=true -Dlifecycle.model=true` 才启动实际 Python Worker 和付费 DeepSeek；常规回归不启动付费模型。模型通过 MQ 消费，Java HTTP、PostgreSQL、RabbitMQ、Silo 与 FFmpeg 均为隔离真实服务。素材复用测试中已准备的静音视频和受控既有红色矩形视觉证据，不新增 ASR／VLM／Embedding，也不将受控证据称为本次真实视觉分析。

前三轮真实模型运行均如实记录失败：第一轮 QUESTION 字符串 `"null"` 导致假等待；第二轮问答通过后，制作因严格时长整数节点类型被误拒；第三轮模型把无当前制作的首次制作解析为继续制作，被 Java 正确拒绝，Python 理解边界现已补有限修正。失败证据保留在本地 `backend/target/lifecycle-model-attempt1/`、`attempt2/`、`attempt3/`，不计通过。四轮实际模型调用分别为 3／9／7／12 次，共 31 次，均有 RECEIVED 回执；最终通过轮为 12 次，不把失败轮费用隐去。

真实模型目标为讨论→证据问答→2 秒竖屏无字幕制作→同制作改为 1.5 秒，核对条件、规范化草稿、历史只读及可播放预览。桌面／手机布局截图与实际播放时间推进属于自动浏览器证据。静音单场景不能验证讲话素材音画／字幕质量，完整人工观看试听仍需单独记录，不能由元数据或自动解码替代。

真实模型脱敏摘要见[本轮模型记录](conversation-lifecycle-step7-live.json)。实际下载预览 FFprobe 为 1.5 秒、360×640、H.264／AAC，完整 FFmpeg 解码通过，静音测量最大 -91 dB；检查覆盖全片的 6 个采样帧，红色矩形及源画面文字保持、按比例留黑边。该检查是自动媒体验证和帧图核查，仍不冒充人工连续观看或讲话字幕试听。

## 本地部署

已以 `docker compose up -d --no-build --wait --wait-timeout 180` 更新本地部署。业务镜像 `82fb53913884`、Worker 镜像 `bf6b9135344f`。PostgreSQL／RabbitMQ／业务 API 健康检查通过；Qdrant、Silo 和 Worker 运行。`agent.v1` 只有 1 个消费者，ready／unacknowledged 均为 0。

数据库 V53→V57 四份迁移均成功。迁移前后、部署冒烟之前的账号／项目／素材数量完全一致。迁移前以 pg_dump 生成并用 pg_restore --list 验证可读的私有备份 `agent-service/.local/deploy-backups/before-conversation-lifecycle-step7.dump`，权限 600，不纳入 Git。原 PostgreSQL、Silo、RabbitMQ、Qdrant、工作卷和 agent_state 原样保留；Compose 提示既有 agent_state 非 Compose 创建，未改卷或清除回执，Worker 实际读写和执行正常。

已核对部署首页与本机生产 dist 字节一致，容器内提示词／模型边界源码与最终本机源码 hash 一致。部署后另用独立测试账号在实际 Compose 服务完成注册、登录、空项目／新聊天、真实 DISCUSS、恢复答复、无制作／方案及退出；未上传或重新分析素材。首次冒烟脚本误把欢迎语计为重复答复，实际任务已成功；修正断言后重跑完整流程通过。两次冒烟共 5 次模型调用，均 RECEIVED，其中一次真实字符串 `"null"` 按新边界在原预算内修正成功；不把夹具失败隐藏为首次全过。

入口 [http://localhost:8080](http://localhost:8080) 已在 Codex 浏览器打开并保留，登录／创建账号页可见。业务重启需要重新登录。独立测试账号创建的两份空项目用于部署冒烟，不变更原素材。

本轮实现、代码审查、非付费自动回归及受控／真实模型浏览器验收已完成；**人工连续观看、讲话音画与字幕试听仍未执行，完整人工验收尚待用户亲自体验确认**。不能将这些自动检查写成完整设计的全部人工验收已结束。

## 复现

```sh
npm --prefix frontend run build
# 在独立终端运行开发服务器，再运行 HTTP 边界回归
npm --prefix frontend run dev -- --host 127.0.0.1
E2E_BASE_URL=http://127.0.0.1:5173 npm --prefix frontend run test:e2e -- conversation-lifecycle.spec.ts
# 显式付费验收，复用隔离环境已分析素材
mvn -q -pl backend -Dtest=LocalMediaIntegrationTest#unacceptedAndSavedSilentDraftsRenderThroughMqWithTheirOwnSources -Dlifecycle.browser=true -Dlifecycle.model=true test
# 最终非付费回归（Maven 串行）
RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp -Dlifecycle.browser=true verify
agent-service/.venv/bin/python -m pytest agent-service/tests -q
agent-service/.venv/bin/python -m compileall -q agent-service/agent_service agent-service/tests agent-service/scripts
./scripts/start.sh
```
