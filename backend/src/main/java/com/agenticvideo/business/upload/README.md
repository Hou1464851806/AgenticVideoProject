# 分片上传、续传与清理

> 接纳浏览器文件上传，管理分片签名、写入资格、完整对象提交、容量预留及取消／过期清理。

## 1. 模块定位与边界

前端 uploads.ts 创建上传会话并直接 PUT 到 Silo；Java 保存上传身份、核对存储分片，再创建待验证素材与 MEDIA_PREPARE 任务。依赖 project 的归属检查、storage 的容量核算、PostgreSQL 和 S3 兼容存储。对象完成不等于素材 VALID；本包不执行 FFmpeg 或模型分析。

## 2. 快速导航

入口：[UploadController.java](UploadController.java)。本包随 Java 应用启动，没有独立进程；运行、配置与开发工具见[后端 README](../../../../../../../README.md)。下文命令均从仓库根目录执行。

## 3. 目录与文件职责

| 路径 | 职责 |
| --- | --- |
| [UploadController.java](UploadController.java) | 浏览器上传／素材列表路由、参数校验和项目授权 |
| [UploadService.java](UploadService.java) | 8 MiB 分片规则、幂等创建、writerToken 资格、续传、完成及预留核对 |
| [MultipartStorage.java](MultipartStorage.java) | S3 multipart 创建／列举／签名／完成／终止、对象检查与播放签名 |
| [UploadCleanup.java](UploadCleanup.java) | 取消、过期、在途操作核对与持续清理、配额释放 |
| [UploadErrors.java](UploadErrors.java) | 存储不可用、空间不足和上传状态冲突的错误映射 |

## 4. 架构与内部交互流程

```mermaid
flowchart LR
  B[浏览器文件与 SHA-256] --> U[创建会话及预留]
  U --> S[Silo 分片直传]
  S --> C[服务端核对并完成对象]
  C --> D[(素材和媒体任务及 Outbox)]
  D --> M[Java 媒体验证]
```

1. create 在账号／项目锁和容量检查下保存 upload_sessions 与 storage_reservations，相同幂等键必须对应相同文件与页面身份。
2. 初始化 multipart 后返回 writerToken；每次最多为 3 个分片签名。浏览器上传后报告 ETag／字节数，服务端结合真实存储核对。
3. 暂停／恢复先核对大小、SHA-256、writerId 与写入租约；resume 返回存储已有分片，仅读取状态不延长恢复期限。
4. complete 核对分片并完成对象，写入待验证素材、MEDIA_PREPARE 和 Outbox；响应 202，不把素材直接标为可用。
5. 取消／过期先停止后续调度并释放素材名额；UploadCleanup 核对在途 PUT 和对象残留，实际清理后释放字节占用。

## 5. 公共接口与数据契约

| HTTP | 输入 | 输出／行为 |
| --- | --- | --- |
| `POST /api/v1/projects/{projectId}/uploads` | filename、sizeBytes、sha256、idempotencyKey、writerId | uploadId、writerToken、分片与预留信息 |
| `GET /api/v1/uploads/{id}`、`GET /api/v1/projects/{projectId}/uploads` | UUID；列表可带 cursor | 上传／验证／清理状态及分页 |
| `POST /api/v1/uploads/{id}/part-urls` | writerToken、partNumbers | 签名地址、headers、预期字节数 |
| `PUT /api/v1/uploads/{id}/parts/{number}` | writerToken、etag、sizeBytes | 服务端确认分片 |
| `POST /api/v1/uploads/{id}/resume` | writerId、sizeBytes、sha256 | 写入资格与已存分片 |
| `POST /api/v1/uploads/{id}/pause`、`/complete` | writerToken | 暂停状态或待验证素材／任务 |
| `POST /api/v1/uploads/{id}/cancel` | 当前账号 | 取消及清理状态 |
| `GET /api/v1/projects/{projectId}/assets` | includePending，默认 false | 项目素材列表 |

外层为 data/requestId；声明哈希为 64 位十六进制 SHA-256。文件内容哈希最终仍由媒体验证核对。

## 6. 依赖与配置

`UPLOAD_MAX_FILE_BYTES=2147483648`、`UPLOAD_MAX_PROJECT_BYTES=5368709120`、`UPLOAD_MAX_PROJECT_ASSETS=10`；分片固定 8 MiB。`UPLOAD_INACTIVITY_SECONDS=86400`。清理使用 `UPLOAD_CLEANUP_SETTLE_SECONDS=30`、`UPLOAD_CLEANUP_INTERVAL_MS=10000`。`STORAGE_PART_URL_SECONDS=300`；对象存储地址、凭据见根配置，S3 bucket 应用默认 originals。容量开关与预算由 storage 管理。

配置生效方式及 Compose 可覆盖范围见[根配置说明](../../../../../../../../README.md#8-配置说明)；Spring 配置来源为[application.properties](../../../../../resources/application.properties)。

## 7. 错误处理与可观测性

同幂等键内容不同、文件身份不符、写入租约失效、配额超限返回 409／UPLOAD_CONFLICT；空间不足为 STORAGE_SPACE_LOW。S3 SDK 异常映射 503／STORAGE_UNAVAILABLE。清理保存待重试状态并周期复核，不能提前宣称字节已释放；已发布素材不走上传取消删除。

## 8. 测试

同名测试包还包含 UploadLowSpaceIntegrationTest，以及 SiloFaultProxy／UploadTestApi 夹具；覆盖真实 Silo、并发配额、失败与清理。前端 upload-resume／upload-cancel／upload-limits 覆盖交互；成功上传共享部署可能触发真实分析费用。

```bash
mvn -B -ntp -pl backend -am test -Dtest=UploadApiIntegrationTest,UploadExpiryIntegrationTest,UploadQuotaIntegrationTest,UploadGlobalCapacityIntegrationTest,UploadSpaceRecheckIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试源码见[后端测试目录](../../../../../../test/java/com/agenticvideo/business)；集成测试需要 Docker，媒体测试另需宿主 FFmpeg／ffprobe。此处是相关回归命令，文档改动不执行应用测试。

## 9. 扩展与维护

调整上传状态时同步核对写入租约、过期保护、清理与容量释放；不可只改页面状态。新增存储操作继续由 MultipartStorage 承担协议细节。修改分片规则须同时检查前端 uploads.ts 和已有持久上传的兼容性。分片签名不是物理硬配额。

## 10. 相关文档

[根 README](../../../../../../../../README.md) · [后端模块地图](../../../../../../../README.md) · [领域术语](../../../../../../../../GLOSSARY.md) · [asset](../asset/README.md) · [storage](../storage/README.md) · [media](../media/README.md) · [project](../project/README.md)

[upload-design.md](../../../../../../../../docs/upload-design.md) · [issue-8.md](../../../../../../../../docs/verification/issue-8.md)
