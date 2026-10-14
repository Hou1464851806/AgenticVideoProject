# Java 工具 M1：第四阶段 Python 接入记录

日期：2026-10-15（Asia/Shanghai）。依据[已确认的最终设计](../java-tools-m1-design.md)完成第四阶段接入与针对性验证。保留既有 Java 改动、持久数据及付费回执；完整 M1 尚待第五阶段验收。

## 实现与边界

- `tools/java.py` 实现 `JavaToolExecutor`：HTTP 传输绑定 Java 创建的问答范围，使用本机内部 Bearer 认证；模型只提供工具参数，凭据与 URL 不写入模型上下文或 checkpoint。
- CLI 增加 `--java-url/--project/--asset`；沿用 LangGraph、SQLite、预算、等待补答和恢复入口。没有新增 Python 服务、MQ 消费者、回调平台或自动分析等待。
- checkpoint 保存 `question_scope` 的身份及完整原元数据。补答／故障恢复 GET 原范围，核对完全一致后注入素材，不读取最新 heads；普通后续消息在原素材范围创建新快照并清除旧观察与覆盖。解析出的新需求分支也沿用本轮已经创建的快照，不再次取 heads。
- 真实模式能力目录仅开放四读取工具与 QUERY；请求理解及动作检查共同阻止细读、草稿和制作，明确不可用，不进入 Mock。Mock 离线场景及自定义仅 execute 执行器仍保留。
- `tools/evidence.py` 集中验证证据身份、来源及时间，观察与最终引用共用。语音保留 `INTERVAL`、原音轨时间和上下文；视觉必须为 `POINT/observed_at_ms`，不制造区间、不扩张为全片覆盖。
- 素材列表按页累积，目录分页完成与原来源分析覆盖分别保存；反馈保留通道降级和未就绪状态。安全、依赖、执行未核对或传输不确定直接有限收尾；参数错误才进入模型修正。传输中断不自动重试、不当作空结果。
- 最终答案、错误收尾、取消和工具结果均使用当前 `mock` 标记。Langfuse 工具 span 保存真实调用 ID／请求哈希，模型与图 metadata 关联执行和范围身份。能力常量位于无 I/O 的目录模块，HTTP 与证据规则各自集中，不引入动态工具框架。

操作命令及复现脚本见[Python README](../../agent-service/README.md)，Java 接口见[后端 README](../../backend/README.md)。`httpx==0.28.1` 从已有锁定版本明确声明为直接依赖，没有升级其他库。

## 受控行为测试

最终源码运行 `agent-service/.venv/bin/python -m pytest agent-service/tests -q`：**67通过、0失败、1跳过**，约6.25秒。跳过项为旧 Python 百炼付费测试，没有将其计为真实模型通过。

新增行为验证包括：补答沿用原快照；新普通消息取得新版本并清除旧观察；模型提交不确定恢复不重付；HTTP传输不确定停止且仅调用一次；点时刻边界、来源和引用一致性；真实能力拒绝；素材分页累计；原范围变化和 Mock 恢复被拒绝；权限／依赖／无效协议分类。既有预算、等待、取消、草稿及工具契约回归仍通过。

## 实际 Java HTTP 与脚本模型

第三阶段真实验收报告的项目／scope 来自独立 Java 集成测试运行，并不存在于正在运行的 Compose 应用数据库；初次按该报告创建范围收到404后停止，没有把其当作空素材或自动替换范围。

本阶段显式选择当前本机数据库已有、带百炼 ASR／VLM成功回执的四个固定素材，保留为[范围清单](java-tools-m1-stage4-samples.json)及[来源模型回执](java-tools-m1-stage4-source-receipts.json)。这里只验证读取与 Agent 接入，未重新上传或重新执行 ASR/VLM；不能据此宣称本轮又完成一次 Java 上传分析链路。

| 固定样本 | 四真实工具 | 最终引用 | 核查 |
| --- | --- | --- | --- |
| human-speech.mp4 | 全部 READY／mock=false | 1条语音 | 告别、篇章结束与开始原文 |
| labelled-speech.mp4 | 全部 READY／mock=false | 5条语音 | 张明、上传素材、核查原文、导出；1436ms音轨偏移 |
| labelled-vision-vfr.mp4 | 全部 READY／mock=false | 8条视觉／文字点 | 红／蓝矩形、证据42／画面17；0、2320、4760、7080ms |
| speech-mention-only.mp4 | 全部 READY／mock=false | 1条语音 | 提到猫仍为语音来源，没有转成视觉引用 |

四样本的 DENSE／BM25／LITERAL 通道均为 AVAILABLE。原请求、结果、范围和引用保存在[脚本运行JSON](java-tools-m1-stage4-scripted.json)。脚本决策不代表真实模型推理能力，也不生成假的 generation。

## 真实 DeepSeek 与观测

真实 `deepseek-flash` 完成两次问答，分别为 `ANSWER/mock=false`：

- labelled-speech：5条真实语音引用；回答姓名张明和上传／核查／导出步骤，说明视觉来源未开始，不推断画面。
- labelled-vision-vfr：8条真实点引用；回答红色／蓝色矩形与证据42／画面17，明确四个采样时刻和视觉覆盖不完整，不推断全片不存在其他内容。

结论按仓库原始视频、[语音人工脚本](../../frontend/e2e/fixtures/labelled-speech.json)及[视觉人工标签](../../frontend/e2e/fixtures/labelled-vision-vfr.json)核查，未把模型生成的答案当作正确答案。语音“导出”的转写没有包含标签脚本最后的“短视频”，不宣称逐字准确率通过。

[真实问答JSON](java-tools-m1-stage4-deepseek.json)保留全部工具身份、原始反馈、最终来源、用量及 trace ID。两个 trace 分别有5／5个真实 generation，各2个真实工具 span，总 token 为20355／20268；请求／格式的有限修正分别1／1次，不影响引用身份校验。DeepSeek 自主选择目录和范围读取，没有为满足流程强制调用四工具；四工具接入由上面的独立脚本验收覆盖。

使用 `scripts/verify_langfuse.py` 从实际本机 Langfuse 读取并核对了执行身份、generation用量、工具调用 ID／hash、真实标记、范围关联、全部 trace 输入／输出无 `mock=true` 和凭据缺席，结果见[观测报告](java-tools-m1-stage4-traces.json)。Langfuse 的传播 metadata 会将布尔序列化为字符串 `False`，工具输出 JSON 仍为布尔 `false`；核对脚本兼容该服务表示，未将其解释为 Mock。

## 跨进程补答与故障恢复

`verify_java_resume.py` 使用实际 Java HTTP、脚本模型和 CLI 子进程验证，结果见[恢复JSON](java-tools-m1-stage4-resume.json)：

1. 首个 CLI 进程创建真实范围并停在 USER_INPUT；另一 CLI 进程补答后读取原始证据并完成答案。scope全文与 session／requirement／execution／turn身份均未改变。
2. Java 完成范围读取并持久保存回执后，在 Python保存反馈之前注入执行异常；新的 CLI 进程 `resume` 读取原scope，使用checkpoint中同一 pending request／call ID／hash恢复，工具预算不新增。
3. 恢复后再次重放同一请求，与异常前 Java 回执逐字段一致。没有创建新快照、改读 heads 或启动分析／付费模型。

该故障是可重复的进程间恢复验证，未宣称本阶段做过SIGKILL或MQ ACK前断连。来源晚发布／版本变更对补答恢复的隔离由受控Python行为测试与第三阶段Java冻结测试共同验证，没有修改本机付费分析head制造故障。

## 剩余范围

第四阶段完成 Python 接入及问答、补答、恢复验证。完整 M1 的上传到问答综合验收、保留语义查询集、MQ ACK前断连／SIGKILL／积压并发量测、媒体人工核查及必要回归／废弃路径清理仍按第五阶段推进，不将既往或未运行项写成通过。
