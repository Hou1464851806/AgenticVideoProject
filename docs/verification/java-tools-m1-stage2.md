# Java 工具 M1：第二阶段实施记录

日期：2026-10-15（Asia/Shanghai）。依据[已确认的最终设计](../java-tools-m1-design.md)继续第二阶段。保留第一阶段工作区改动，未重置数据或提交历史。完整 M1 尚未验收。

## 已落地

- Java `bailian` 包内三个具体客户端分别适配异步文件 ASR、单图 VLM 和文本 Embedding，共享有界 HTTP 传输与认证；未引入统一模型网关或插件框架。
- 上传媒体准备成功后，在业务事务内自动接纳 ASR 与基础视觉任务及 Outbox。无音轨 ASR 不适用；视觉抽帧失败不阻断语音。稀疏视觉最多25帧，实际 PTS 和 `complete=false` 保留。
- `analysis.v1` 有界消费者初始并发2、prefetch1，直接执行并在持久结果/继续意图提交后 ACK；最终失败 Reject 到分析死信。模型与 Qdrant 请求在数据库事务之外。
- ASR 私有音轨下载校验大小和 SHA256，流式上传百炼临时存储。远端任务身份持久化后只查询；等待结束当前步骤并经 Outbox/MQ 安排下一次查询，正常查询不耗尽三次失败额度。30分钟自动查询截止仍保留远端身份。
- VLM 只接收单张已校验 JPEG，保存画面描述与可见文字；相同像素调用去重，重复出现的画面保留不同观察时刻。视觉证据为点、语音为区间并保留原音轨偏移。
- 提交身份先保存，完整模型响应、请求身份和实际用量先持久化再解析。提交未知停止自动重付；已保存响应可以在新执行代次继续解析。成功观察及 Embedding 批次在恢复/索引重建时复用。
- Qdrant 写入未发布代次，回查点归属、数量、向量和稠密/BM25 可查询性后发布。并发来源新增证据会继续构建包含新单元的代次，避免漏掉后完成的来源。
- 查询 Embedding 复用具体百炼客户端，仍保存独立查询调用回执。旧 HTTP Worker、旧 Agent 及按需细读保持不可用。
- 新增 V50（本机分析失败计数和原始响应记录），Compose 默认开启分析。后端 README、根说明和真实验收脚本同步更新。

## 验证

### 百炼真实付费链路

执行 `node scripts/test-models.mjs`，使用临时真实 PostgreSQL、Silo、RabbitMQ、Qdrant 和宿主 FFmpeg，从浏览器 HTTP 上传入口进入 Java 消费链路；未使用模拟模型。2026-10-15 00:15:42 四项均运行通过，0跳过。

| 样本 | 核对 | 结果 |
| --- | --- | --- |
| labelled-speech.mp4 | 识别上传/导出，语音映射保留1436ms偏移，稠密/BM25查询 | 通过；12条证据 |
| human-speech.mp4 | 自然中文中的告别/开始，索引覆盖所有单元并可查询 | 通过；7条证据 |
| labelled-vision-vfr.mp4 | 无音轨ASR不适用，可见文字证据42/画面17，真实时刻0/2320/4760/7080ms，点观察及非完整覆盖 | 通过；8条证据 |
| speech-mention-only.mp4 | 语音包含猫，视觉没有把语音提及变成画面事实，索引可查询 | 通过；7条证据 |

百炼协议核对依据[Qwen-ASR API](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)、[视觉接口](https://www.alibabacloud.com/help/en/model-studio/vision)和[文本 Embedding API](https://www.alibabacloud.com/help/tc/model-studio/text-embedding-synchronous-api)。沿用已确认模型，未升级或扩展模型范围。

脚本缺凭据返回 `UNVERIFIED` / 退出码2；明确开启 live test，检查四项执行且0跳过。普通 Maven 回归不运行付费验收，需与上述独立结果分别报告。

### 故障及业务回归

`LocalAnalysisIntegrationTest` 使用真实上传、数据库、对象存储、MQ 和 Qdrant；模型 HTTP 响应固定，私有提供方上传及转写下载由测试替身代替。该测试用于业务故障不变量，不替代上面的真实模型验收。覆盖重复消息、重建复用成功批次、安全索引写入失败重试、ASR多次正常等待与废弃代次恢复、丢失付费响应停止重付、解析失败保留原响应和用量。

首次扩展 ASR 等待测试时，测试提供方复用了固定远端UUID，触发真实数据库身份唯一约束；修正为每次提交独立UUID，未放宽生产约束或断言。

最终 `mvn -B -ntp -pl backend -am verify` 于2026-10-15 00:29:04通过：140项中136项执行通过，0失败/错误；4项付费验收在普通回归中按显式开关跳过，已经在上面的独立真实运行中4/4通过。新增分析集成测试5/5、媒体5项、Outbox1项以及上传/权限/方案/渲染/会话历史契约回归均通过，最终源码重新打包。

Compose配置校验和 `git diff --check` 通过。没有把普通回归中的跳过计作真实模型成功。

### 容器与浏览器

`docker compose build business-api` 成功；`docker compose up -d --no-build --wait --wait-timeout 120 business-api` 成功，新应用健康，保留原数据库/存储卷。运行数据库从 V49 迁移至 V50，媒体消费者1、分析消费者2，工作队列消息数0。启动约17秒（本次同时运行整轮集成测试）。

```sh
cd frontend
npx playwright test e2e/projects.spec.ts e2e/media.spec.ts \
  --grep 'two browser accounts|local MQ consumer' --reporter=line
```

两项均通过（20.2秒）：真实浏览器上传、MQ媒体准备、授权播放/Range206、定位、签名续取、跨账号拒绝、窄屏工作区。

该浏览器测试的 short-video.mp4 含测试音调，百炼ASR实际返回 `FAILED / SUCCESS_WITH_NO_VALID_FRAGMENT`。数据库保存原提供方终态，不伪装为成功或重新提交；该任务进入分析死信（新增1条）。同一素材 `VISION_ANALYZE` 和 `INDEX_BUILD` 成功，证明语音失败没有阻断视觉和派生索引。旧媒体死信11条保留，未清空队列以制造验收结果。

## 边界

第二阶段固定样本验证代表这些视频的实际结果，不代表一般ASR/OCR准确率或完整视觉覆盖。ASR远端失败后手工重试仍须核对已知失败状态；结果不确定不能借重投或重试接口重复付费。

尚未实现第三阶段问答范围快照、四工具独立契约与稳定分页，也未接入 Python `JavaToolExecutor`、真实问答及补答/恢复。最终 SIGKILL、ACK前断连、积压并发量测及渲染人工核查仍属于后续完整M1验收，不能由本阶段废弃代次模拟代替。
