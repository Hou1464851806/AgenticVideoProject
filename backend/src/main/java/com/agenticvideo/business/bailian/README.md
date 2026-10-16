# 百炼模型协议客户端

> 封装 ASR、视觉观察和文本向量的具体百炼协议，为 Java 分析及检索提供有界 HTTP 访问。

## 1. 模块定位与边界

AnalysisProcessing 调用本包执行提供方动作，SearchVectors 调用 Embedding 生成查询向量。输入是受控媒体／文本及模型参数，输出保留提供方 JSON，再由业务层登记回执和发布证据。本包不持有数据库、不决定付费重试或任务状态，也不调用 DeepSeek。

## 2. 快速导航

入口：[BailianHttp.java](BailianHttp.java)。本包随 Java 应用启动，没有独立进程；运行、配置与开发工具见[后端 README](../../../../../../../README.md)。下文命令均从仓库根目录执行。

## 3. 目录与文件职责

| 路径 | 职责 |
| --- | --- |
| [BailianHttp.java](BailianHttp.java) | 带凭据 HTTP、提供方文件地址限制、响应大小与超时 |
| [BailianAsr.java](BailianAsr.java) | 申请临时上传、提交异步转写、查询任务及读取转写结果 |
| [BailianVision.java](BailianVision.java) | JPEG 视觉请求和结构化观察解析 |
| [BailianEmbedding.java](BailianEmbedding.java) | 文本批次请求及按 text_index 恢复向量顺序 |

## 4. 架构与内部交互流程

```mermaid
flowchart LR
  A[分析或检索] --> H[BailianHttp]
  H --> P[DashScope API]
  P --> R[提供方原始响应]
  R --> B[业务层保存回执并解析]
```

1. 调用前 requireConfigured 检查凭据，业务层先决定本次动作是否允许提交。
2. ASR 使用提供方临时文件通道上传音频，提交取得 task_id；后续查询原身份并下载转写结果。
3. VLM 发送 JPEG 内容和观察提示，解析响应文字中的 JSON；Embedding 按输入批次请求并校验返回向量排列。
4. 响应交还 AnalysisProcessing／SearchVectors；由调用方在远端请求前后登记持久回执，不由客户端自行重复提交。

## 5. 公共接口与数据契约

| Java 类／方法 | 输入 | 输出 |
| --- | --- | --- |
| `BailianHttp.request` | method、path、body、headers | 提供方 JSON 对象 |
| `BailianAsr.upload/submit/query/transcript` | 本地音频、模型或远端任务身份 | 临时地址／提交回执／查询／转写 JSON |
| `BailianVision.observe/observation` | JPEG／提供方响应 | 原始响应／结构化观察 |
| `BailianEmbedding.embed/vectors` | 文本、模型、维度、text_type／响应 | 响应／按索引排序的向量 |

无本项目 HTTP 入口。模型名称和维度由调用方传入，不在客户端选择业务分析代次。

## 6. 依赖与配置

`DASHSCOPE_API_KEY` 无默认凭据；`DASHSCOPE_BASE_URL` 默认 `https://dashscope.aliyuncs.com/api/v1`。连接超时 10 秒、请求超时 90 秒、JSON 响应最多 8 MiB，定义于 BailianHttp。下载／上传的提供方文件地址限定 HTTPS 且主机以 `.aliyuncs.com` 结尾。无独立 Feature Flag。

配置生效方式及 Compose 可覆盖范围见[根配置说明](../../../../../../../../README.md#8-配置说明)；Spring 配置来源为[application.properties](../../../../../resources/application.properties)。

## 7. 错误处理与可观测性

缺凭据、非 2xx、超限、格式错误和中断抛 BailianHttp.Unavailable；中断保留线程中断标记。客户端没有自动重试，业务层区分安全读取与结果未知的付费提交。不要在日志暴露 Authorization 或临时文件地址；持久回执位于上游业务模块。

## 8. 测试

没有同名 bailian 测试包；LocalAnalysisIntegrationTest 使用受控提供方协议，ReadToolsIntegrationTest 覆盖查询向量及降级。真实模型由 JavaAnalysisLiveTest 显式付费验证，日常不默认运行。

```bash
mvn -B -ntp -pl backend -am test -Dtest=LocalAnalysisIntegrationTest,ReadToolsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试源码见[后端测试目录](../../../../../../test/java/com/agenticvideo/business)；集成测试需要 Docker，媒体测试另需宿主 FFmpeg／ffprobe。此处是相关回归命令，文档改动不执行应用测试。

## 9. 扩展与维护

新增提供方协议时只添加当前需要的客户端动作，并让分析／用量层定义回执和恢复策略；不要在 HTTP 客户端加入通用重试。响应解析调整应覆盖原始响应保留和既有回执重放。更换向量模型／维度同时维护 search 索引契约。

## 10. 相关文档

[根 README](../../../../../../../../README.md) · [后端模块地图](../../../../../../../README.md) · [领域术语](../../../../../../../../GLOSSARY.md) · [analysis](../analysis/README.md) · [search](../search/README.md)

[java-tools-m1-design.md](../../../../../../../../docs/java-tools-m1-design.md) · [java-tools-m1-stage2.md](../../../../../../../../docs/verification/java-tools-m1-stage2.md)
