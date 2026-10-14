# Java 读取工具 M1 第五阶段综合验收

日期：2026-10-15（Asia/Shanghai）。结论：M1范围内的综合验收、故障与必要媒体回归通过；未运行及明确不覆盖的项目见下文。本阶段在同一批新上传的固定视频上贯通真实 Java 分析、索引、Python 四工具和 DeepSeek 问答，并补充真实进程故障、MQ 故障及媒体回归。阶段一至四的记录继续保留，本记录不将历史运行算成本轮重跑。

最终提交前的双轴审查、候选上限修复及最新回归结果见[完整收尾审查](java-tools-m1-closeout.md)。本记录保留第五阶段当时的实际运行计数。

## 真实上传到问答

通过现有浏览器公开 HTTP 上传入口创建新项目并上传四份仓库视频；浏览器/Silo 直传、Java 单体、RabbitMQ、PostgreSQL、百炼 ASR/VLM/Embedding、Qdrant 均实际运行。随后以同一批素材 ID 执行 Python CLI 读取和真实 DeepSeek 问答。身份、原始反馈、用量与最终 JSON 分别保存在[样本清单](java-tools-m1-stage5-samples.json)、[来源模型回执](java-tools-m1-stage5-source-receipts.json)、[脚本四工具](java-tools-m1-stage5-scripted.json)、[真实问答](java-tools-m1-stage5-deepseek.json)。

| 视频 | 本轮可核查结果 |
| --- | --- |
| labelled-speech | 姓名张明、上传/核查/导出三步骤，5条语音引用；原始时间=音轨时间+1436ms |
| human-speech | 自然中文语音的告别、篇章结束与开始；语音区间1900–7340ms |
| labelled-vision-vfr | 无音轨，ASR不适用；红/蓝矩形及证据42/画面17；视觉点0/2320/4760/7080ms，未虚构持续区间 |
| speech-mention-only | 语音引用提到猫；视觉采样未提供猫出现证据；答案明确稀疏观察不能证明全片绝无猫 |

脚本决策分别调用全部四工具，4/4 `ANSWER/mock=false`。真实 DeepSeek 也为4/4 `ANSWER/mock=false`，自主选取工具，共18次实际 generation、6次工具读取；脚本运行不算真实模型推理。所有最终引用逐字段对应真实工具回执，额外核对固定样本的姓名、关键词、来源与时间。人工依据是仓库视频及原有标签文件，不是模型答案。ASR有识别误差：合成语音末句未转写“短视频”，“提到猫”样本转写为“提着一只猫”；不宣称逐字准确率通过。

[Langfuse 报告](java-tools-m1-stage5-traces.json)实际读取四个 trace，检查 execution/scope/call ID/hash、generation用量、真实标记及凭据缺席。报告中的本机URL只适用于本机观测服务。

固定的[语义查询集](../../frontend/e2e/fixtures/semantic-queries.json)未改词、权重或门槛。开发3/3、保留3/3命中已标注相关原文，观察到全部位于融合结果前2名，DENSE/BM25/LITERAL均可用；这是五句小样本的混合召回验证，不是独立稠密召回准确率或生产基准。[逐条回执](java-tools-m1-stage5-semantic.json)保留实际结果。

首次脚本联调的“猫”场景误用了要求视觉核验的问题，却只返回脚本语音引用，被现有校验拒绝为INCOMPLETE。已将脚本工具接入验证与真实模型的跨来源问题分开；修复后复用同一批已分析素材重跑读取，没有为了测试脚本再付费执行ASR/VLM。上述报告只将修复后的实际成功运行计为通过。

## 恢复与故障

[跨进程恢复报告](java-tools-m1-stage5-resume.json)使用本轮新素材，验证补答、新CLI进程恢复和回执重放沿用原scope及执行身份。Java受控契约回归还覆盖晚发布来源/新head不改变旧快照、普通新消息取得新快照、依赖失效明确停止、分页/超大记录/参数冲突及通道降级；这些不是通过修改本机付费证据制造的故障。

[真实SIGKILL报告](java-tools-m1-stage5-crash.json)记录本机Java容器在`MEDIA_PREPARE/RUNNING`、attempt=1时被SIGKILL。数据卷保留，重启后旧代次EXPIRED，同一任务经MQ以attempt=2完成。该故障发生在媒体准备阶段，不声称它验证了真实付费提交中途崩溃。

最终Java媒体/分析专项12/12通过、0跳过，并重新打包；[MQ量测](java-tools-m1-stage5-mq.json)记录ACK前断连后2次投递、1个执行代次、2次实际VLM调用，积压至少2条时媒体峰值1。其余故障由真实PostgreSQL/Silo/RabbitMQ/Qdrant与受控提供方HTTP组合验证，避免故障注入真实重复扣费：

- broker停止后数据库任务/Outbox保留，恢复后继续；mandatory未路由和publisher confirm单独验证。
- VLM结果已持久化、listener尚未ACK时关闭实际AMQP连接，要求观察到真实重投，成功付费调用和业务完成记录不增加。
- 媒体消费者被有界测试闸门占住，至少2条消息真实积压，3个上传的实际媒体执行峰值保持1。闸门只位于测试spy，不增加生产开关。
- 有限重试、数据库失败原因与死信；索引写失败重用已保存Embedding；异步ASR继续原提供方任务；丢失付费响应转UNCERTAIN，重复消息与人工重试不重新提交。
- 取消、废弃代次及迟到发布；渲染终态重复消息不重复产物。

## 媒体与回归

本轮完整 `mvn -B -ntp verify` 为146通过、4跳过、0失败/错误，构建成功；4项是显式门控的 `JavaAnalysisLiveTest`，本轮未运行该类，不能把跳过算通过。四视频的实际百炼与Python链路由上面的独立新上传验收运行。Python删除旧适配器后按锁文件sync移除了6个无用包，行为测试67/67通过、0跳过；前端 `npm ci`、类型检查和构建通过。逐类计数见[回归清单](java-tools-m1-stage5-regression.json)。

原素材浏览器回归5/5通过；部署镜像预览/导出专项首轮4通过/2夹具超时，修复后两项独立复验2/2通过，合计11个不同浏览器用例验证通过。复验中还修正了空白页localStorage访问及已确认版本等待按钮的夹具错误；没有用失败轮次冒充整轮通过。后者覆盖真实低清预览、720p/1080p成片、播放器解码、下载与删除、固定版本/确认、片段顺序、无音轨补静音、取消后实际FFmpeg退出、失败后旧成片与原素材保留，不调用旧Agent生成方案。

[媒体量测](java-tools-m1-stage5-media.json)记录实际MP4的ffprobe信息：横屏低清640×360/4秒、竖屏低清360×640/4秒，正式竖屏720×1280及1080×1920/2秒，H264/AAC、48kHz双声道。原声片段与原视频2–4秒在±2ms窗口的最佳波形相关性0.997234，最佳延迟0采样；第一段均方能量4258571.93，第二段静音能量0。核对了[横屏中文字幕](assets/m1-stage5-captions.png)、[竖屏第一段](assets/m1-stage5-portrait-first.png)、[竖屏第二段](assets/m1-stage5-portrait-second.png)和[正式导出](assets/m1-stage5-export.png)，字形、颜色、顺序与contain黑边符合预期。MP4原文件留在本机`.scratch/preview-verification/`和`.scratch/export-verification/`。

首轮Java专项尝试在宿主开启字幕失败：宿主FFmpeg8.1缺少subtitles滤镜。因此宿主Java用例保持原来的无字幕媒体回归，字幕/字体必须在实际部署的FFmpeg6.1.1/Noto镜像验证，不能把宿主字幕失败写成通过。MQ首轮计数断言也曾错误地将4个采样时刻等同4次VLM调用；相同帧哈希本来就复用响应。已改成核对断连时的实际调用计数与重投后的计数，采样数和付费数不再混淆。

两项浏览器专项初轮因Java重启后会话失效、旧会话选区恢复覆盖新选择而超时。夹具已显式重新登录，并等待既有选区恢复完成再选目标版本；不修改产品的会话/版本恢复规则。

未验证/边界：

- 未运行仍依赖旧Python Worker或旧Agent制作的整套历史浏览器付费测试；它们不属于当前受支持的M1入口。按需细读、草稿及Agent到成片仍不在M1范围。
- 未对真实付费请求中途SIGKILL或人为丢失真实提供方响应；费用不确定、已知任务恢复和重复投递防重付由受控提供方测试验证。真实SIGKILL只验证本机媒体阶段。
- 未进行人工听音或主观音质评分；原声/静音/时间对齐使用实际MP4的波形和解码量测验证。未将其表述为主观听感验收。
- 未验证多实例、集群高可用、长时间吞吐或完整视觉覆盖；M1为本机单实例和稀疏视觉。

本轮综合链路复用同一批新上传ID，脚本场景修复后分段重跑读取/问答/观测，没有再执行一次完整的重复付费上传命令。新入口的各段均已真实运行，未将一次失败的整条命令写成成功。

## 执行入口与清理

```sh
mvn -B -ntp verify
mvn -B -ntp -pl backend -am verify -Dtest=LocalAnalysisIntegrationTest,LocalMediaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
agent-service/.venv/bin/python -m pytest -q agent-service/tests
agent-service/.venv/bin/python agent-service/scripts/verify_java_m1.py --output agent-service/.local/stage5-new
agent-service/.venv/bin/python agent-service/scripts/verify_java_crash.py
./scripts/start.sh
cd frontend
npm ci
npm run build
DOCKER_BIN=/usr/local/bin/docker npx playwright test e2e/media.spec.ts e2e/previews.spec.ts e2e/exports.spec.ts e2e/export-history.spec.ts
```

`DOCKER_BIN`须设为本机Docker可执行文件；本次Node子进程PATH未包含它，首轮媒体测试未执行，显式设置后重跑。综合验收命令会上传新视频并产生模型费用；缺凭据返回UNVERIFIED/退出码2。SIGKILL脚本会中断并重启本机Java服务，保留所有数据卷。

删除旧Python百炼/视觉/索引适配器及其旧live测试、boto3直接依赖，重新生成锁文件。requests仍是LangChain/Langfuse的传递依赖。Compose删除旧Python服务/profile及无用途的agent工作区挂载，移除两个已停止的旧容器，不删除历史数据卷。媒体浏览器测试改用单体镜像/进程，删除旧HTTP完成回报代理；原自然语言制作断言不再混入M1的按钮媒体验收。旧Worker协议仍默认410；少量Java legacy实现/测试作为尚未重做的历史业务规则夹具保留，legacy开关不是受支持运行形态。
