# M2 阶段 5：真实素材验收与收尾回归

日期：2026-10-15（Asia/Shanghai）。起始提交 `8bb57c6`，分支 `main`，工作区干净。依据系统临时目录的阶段4交接、[M2 最终设计](../java-tools-m2-design.md)和[阶段4记录](java-tools-m2-stage4.md)，实施阶段5。验收入口沿用 Agent／CLI、Java HTTP 和真实 PostgreSQL；不新增制作入口、模型裁判或编辑能力。

## 本轮改动与失败到通过

真实素材沿用 [M1 样本清单](java-tools-m1-stage5-samples.json)，项目 `71516826-c977-44c5-b682-668a7483a0ac`。运行中的镜像早于 M2，本轮重建并部署当前 Java，执行既有迁移，保留业务数据卷。没有上传新素材或重新启动基础分析。

新增 `verify_java_m2.py`，每个场景显式执行真实 CLI 制作、status、save、重复save及跨进程resume，导出权威回执；新输出目录避免误覆盖付费运行。模型是 deepseek-flash，关闭thinking，不回退脚本或Mock。模型SQLite回执与Java工具回执关联原条件、草稿、哈希、校验、scope、保存来源和永久输出身份；原始stdout/stderr及SQLite保留在本机 `.local/m2-stage5-*`。

真实联调暴露并最小修正了两个状态／反馈问题：

- 多素材请求一轮返回两个 Action。SDK的解析器只取第一条，但适配器拒绝了整轮；旧反馈未说明数量和串行修正方式。现在提供数量及单步指导，拒绝时不再返回被SDK截取的value，提示词明确每轮只调用一次。没有接受额外调用或增加重试上限。
- 原始证据累积后，模型缺少已完成范围读取的记录，反复读取直到无进展停止。coverage增加 `_range_reads` 的请求范围、已消费页和连续分页进度；新页或推进连续链计为进展，重复页不计。跳页不能宣称完成，分页消费不冒充分析覆盖；Java仍独立使用数据库回执核验上下文，不信Python摘要。

补充提示词说明：仅说“生成剪辑”时可以先读已固定素材再选内容；素材start/end已经包含音轨偏移，不能使用audio坐标选段；重排移动完整输出对象，不能重编号output_key。补读不会更新旧validation issues，完成补读后需重新提交DRAFT；因此补读可原样重交草稿。保留原有模型和工具预算、3次修正、内部错误上抛以及显式保存边界。

两条新增测试均先失败后实现通过：协议测试先因反馈没有调用数失败；Agent公共入口分页测试先因缺少范围消费记录失败。随后直接相关Python模块61/61通过；Java校验、契约及schema模块32/32通过。Python未配置静态类型检查器，compileall是编译检查，不报告为类型检查。

## 真实模型验收

完整证据见 [成功及未完成场景](java-tools-m2-stage5-real.json)、[试跑失败记录](java-tools-m2-stage5-attempts.json)。下面时长均为源区间求和，不是编码后帧精度。

| 场景 | 结果与关键证据 |
| --- | --- |
| 单素材／单输出，仅“生成剪辑” | DRAFT_READY，默认横屏、字幕开启、无硬时长；8160ms；显式保存方案 `705cdf88-419c-4479-b63c-c90e7a0dbc68`、版本 `21a23388-d415-4105-a816-74afaf7c55c8` |
| 多素材／多输出及多轮修改 | 初稿9440／8160ms；第二条缩为严格4000ms、改名并排前，第一条完整对象不变；同scope、新校验。保存方案 `7d91fe7c-7fdf-4e19-875b-5b70410dd7e2`、版本 `d1ead956-1120-4361-b4d0-25f4cd97d90e`；输出键顺序out-2／out-1 |
| 单素材／多输出、 多素材／单输出 | 分别6800／3360ms及9440ms，均保存并重复恢复；版本 `4ed1278d-77cc-47fe-9df1-eedd6114c430`、`da38d61b-5a17-45bd-a998-eab1fbe793ee` |
| 视觉POINT／竖屏／字幕关闭 | 3000ms，真实红／蓝矩形及可见文字观察；PORTRAIT_720，cues为空。保存方案 `9cf7726b-6df0-48dc-9ab4-26cddd26db88`、版本 `3cc6e1e5-55d5-49c1-b044-31f10536df16` |
| 14.044秒素材要求严格60秒 | INCOMPLETE，60000ms条件保持，无版本；模型在草稿前说明不足，未制造Java校验回执 |
| 试跑中的严格6秒多轮修改 | 模型错误计算区间总和，Java拒绝，修正预算耗尽后INCOMPLETE，没有保存。4秒场景使用已知完整上传／核查语句验证可满足修改；未把6秒失败隐藏或计为成功 |

多素材单输出的失败试跑（需求 `9c95f8fd-f1af-4e23-a6e2-b3c51405572e`）经历search命中草稿→Java拒绝未读上下文→补读后仍重复读取→模型额度耗尽，没有通过或保存。正式成功需求 `ae80d0c3-42f7-43d9-94e7-fdc6835c72ac` 经两素材目录、一次越界读取被拒、合法补读及一次通过校验后保存；没有将两次制作拼成一条成功修正轨迹。六个正式场景共有32次已收到的DeepSeek调用；四素材问答另有16次DeepSeek、2次查询Embedding提交，身份与用量见JSON。试跑及一次诊断探针费用另记在失败证据，未将失败计入正式成功率。

各成功制作均先返回DRAFT_READY，之后才由验收命令显式运行CLI save；重复save及新进程resume得到相同plan/revision/output身份。读取数据库版本包含完整render/clips/subtitles和制作来源，保存结束该制作。

已验场景前后基础分析调用数保持ASR 3、VLM 5、素材Embedding批次10，分析head保持不变。它们是同项目累计回执数，不是本轮新费用；本轮会发生DeepSeek费用，问答主题检索可能产生查询Embedding费用。未报告未核实的金额。

真实模型不是稳定性或准确率基准。试跑还出现多个调用、错误kind、无理由的主题澄清、音轨坐标误用及重排键错误；错误未越过校验和保存边界。记录失败轨迹，不能由少量成功证明任意主题或时长都能一次成功。

## A1～A14 证据对应

确定性边界在本轮重新运行，使用受控模型／HTTP以及隔离真实数据库。它们不是付费模型效果或真实网络故障验收。

| ID | 本轮证据与范围 |
| --- | --- |
| A1 | 上述真实CLI场景、Java读取／校验回执、原证据引用；基础分析前后不变 |
| A2 | single默认条件及multi局部时长／标题／顺序修改；output_key绑定、新校验、其他输出完整保留 |
| A3 | DraftValidationIntegrationTest：missingContextAndSkippedPages、sentenceWithoutWordTiming、identityConflicts、frozenAnalysis等，非法区间／引用／分页／哈希拒绝 |
| A4 | pointEvidenceDoesNotExpand、boundariesUseAllIntersectingSpeech、sentenceWithoutWordTiming、missingAsrCoverage；真实visual补充POINT制作 |
| A5 | legalMultipleOutputs及重叠／逐输出时长用例；真实60秒与6秒未完成，不放宽、不保存 |
| A6 | boundariesUseAllIntersectingSpeechAndSubtitlesOffCannotBypassForbiddenWords、forbiddenLiteralCannotBeHiddenByTheAsrSentenceSplit、forbiddenLiteralSpansClips |
| A7 | legalMultipleOutputsPersistNormalizedCaptions、audioPrerollUsesOriginalOffset、completeSubtitles、missingAsrCoverage；真实多素材偏移和visual字幕关闭 |
| A8 | feedbackAggregatesAndExplicitlyTruncatesIssues、validationAndReceiptCommitTogether；Python有限修正／完整反馈／无进展测试，真实失败保留草稿和问题 |
| A9 | frozenAnalysisSurvivesNewHead、invalidDependencies；Python制作编辑／补答／进程恢复／明确NEW与M1普通消息快照测试；真实multi沿用scope |
| A10 | saveRejectsMismatchedIdentityHashesAndUnapprovedOrSupersededDrafts；真实先DRAFT_READY、修改重校验、显式保存 |
| A11 | failureAfterRevisionAndReferenceWritesRollsBack、concurrentSaves、pythonAgentRepairsRealValidationThenExplicitCliSaves；Python保存响应丢失夹具；真实重复保存／resume |
| A12 | savedRevisionExposesItsSource、explicitSavePublishesAllOutputs；真实保存版本及来源；Python已保存只读／新制作测试 |
| A13 | savedRevisionExposesItsSourceAndFeedsTheExistingPreviewAssembler及LocalMediaIntegrationTest实际FFmpeg回归；不宣称新Agent已接通预览／导出 |
| A14 | [本轮四素材真实DeepSeek问答](java-tools-m2-stage5-qa.json)：4/4 ANSWER，区分语音、视觉POINT及稀疏覆盖；ReadToolsIntegrationTest、PythonM1／快照／协议与媒体回归，阶段收尾完整检查见下表 |

## 审查与最终检查

审查基准固定为 `8bb57c6`，候选实现 `726773a`；按code-review技能分别开展两名独立子代理审查。

### Standards

发现1项P2：验收脚本未保存原始stdout，空／非法JSON可使解析异常遮蔽CLI失败。修正后，解析前保存stdout、stderr、命令和退出码，明确报告失败与日志路径，保留异常因果链。使用真实CLI连接本机关闭端口复现，修正后可检查ConnectError原始记录；未发生模型费用。

### Spec

发现2项P2：status和退出码未设验收门槛；记录把两次不同制作的失败／成功轨迹串成一个场景。现在核对status为DRAFT_READY及草稿、条件、校验和scope一致，检查每命令退出码；文档按9c95／ae80需求身份分开说明。原status误通过探针现在抛AssertionError且无PASS，新增5条报告边界回归通过。六场景原始真实结果通过最终脚本门槛离线重放；这是报告逻辑验证，没有重复调用模型，不替代原始真实运行或完整回归。

两轴复审 `git diff 8bb57c6...df67885` 通过：Standards原1项已修复、未解决0项；Spec原2项已修复、未解决0项，无新增发现。两轴分别保留结果，没有把其他轴通过当作免责。

### 阶段最终回归

业务及测试代码固定为 `df67885`，独立复审后执行一次完整非付费回归及后端打包，不引用阶段4历史结果。

| 检查 | 最终结果 |
| --- | --- |
| Java `RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp verify` | 16:14:32完成，8分58秒；185项：181通过、4项JavaAnalysisLiveTest付费门控跳过，0失败／错误；Spring Boot后端打包成功 |
| Python `agent-service/.venv/bin/python -m pytest agent-service/tests -q` | 95/95通过，0失败／跳过，11.83秒 |
| 编译／类型 | Python核心、测试及验收脚本compileall通过；未配置Python静态类型检查器。Java主代码及测试随verify由javac编译通过 |
| 文档与差异 | 最终8份Markdown的147个本地相对链接、3份JSON解析和git diff --check通过 |

完整回归后没有业务／测试代码修改，收尾仅更新后端运行说明、实施状态与本记录；最终代码实际通过上述检查，没有使用旧结果替代收尾代码验证。阶段5退出标准通过，M2真实制作／校验／保存范围已交付。完整Java检查覆盖M1四工具／分页／快照、53个迁移、认证、上传、MQ／Outbox、制作校验及保存、版本消费、实际FFmpeg媒体与对话恢复；受控故障日志不等于测试失败，4项门控跳过不计为通过。

本轮未修改前端，未运行浏览器验收；镜像重建中的既有前端类型检查和构建成功，仅是启动当前后端的构建步骤。没有新增Agent按需细读／预览／导出，没有把保存方案称作成片。实际网络响应丢失、真实进程故障不在本次付费验收范围，相应恢复行为通过确定性夹具验证。
