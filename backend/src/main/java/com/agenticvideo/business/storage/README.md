# 文件系统容量与写入预算

> 在上传和媒体任务接纳前核对真实文件系统余量及数据库预留，防止并发任务透支未来写入空间。

## 1. 模块定位与边界

由 `UploadService`、预览／导出请求及分析恢复等内部调用触发；输入未来字节预算，成功表示当前事务可继续预留，空间不足则拒绝接纳。依赖 PostgreSQL 预留／任务记录和 Java NIO 文件系统信息。不实现 S3 对象 API，也不提供存储系统物理硬配额。

## 2. 快速导航

入口：[StorageCapacity.java](StorageCapacity.java)。本包随 Java 应用启动，没有独立进程；运行、配置与开发工具见[后端 README](../../../../../../../README.md)。下文命令均从仓库根目录执行。

## 3. 目录与文件职责

| 路径 | 职责 |
| --- | --- |
| [StorageCapacity.java](StorageCapacity.java) | 上传／渲染预算计算、跨项目事务锁、文件系统余量及已排队预算核对；本目录无子目录 |

## 4. 架构与内部交互流程

```mermaid
flowchart LR
  C[上传或渲染事务] --> B[计算未来字节预算]
  B --> L[事务 advisory lock]
  L --> F[文件系统最小可用量]
  L --> D[数据库未释放预留]
  F --> A[允许或拒绝接纳]
  D --> A
```

1. 调用方在数据库事务内计算上传或渲染的后续写入预算。
2. `require` 取得 `pg_advisory_xact_lock(8072026)`，串行核对跨项目预留。
3. 对配置的多个路径读取最小 usable space，并合计上传及待执行媒体任务的未来写入。
4. 留出安全余量、检查全局预留上限；调用方必须在同一事务结束前写入自己的预留。执行前媒体层还会再次检查工作区。

## 5. 公共接口与数据契约

| Java 接口 | 输入 | 输出／约束 |
| --- | --- | --- |
| `uploadBudget(long)` | 原文件字节数 | 两份原文件预算加 300 MiB 派生／临时预算 |
| `renderBudget(JsonNode)` | 服务端固定 sources、output、configuration | 渲染未来字节估算 |
| `require(long)` | 新增预算 | 正常返回或 SpaceUnavailable；调用方持有事务 |

没有 HTTP 端点。容量预算与项目原始素材配额不同；不能把原文件大小直接当作渲染峰值。

## 6. 依赖与配置

`CAPACITY_PATHS` 映射 `capacity.paths`，应用默认临时目录，Compose 固定 `/capacity/silo,/work`。`CAPACITY_MIN_FREE_BYTES` 默认 536870912；`CAPACITY_MAX_RESERVED_BYTES` 默认 9223372036854775807。实际对象卷需挂载到核算路径；仅配置一个容器内部无关目录会失去意义。

配置生效方式及 Compose 可覆盖范围见[根配置说明](../../../../../../../../README.md#8-配置说明)；Spring 配置来源为[application.properties](../../../../../resources/application.properties)。

## 7. 错误处理与可观测性

读文件系统失败或预算超限抛 `SpaceUnavailable`／409；HTTP 经 `UploadErrors` 映射为 `STORAGE_SPACE_LOW`。不吞掉核对失败，也不把不能测量当成空间充足。无独立重试或监控采集。

## 8. 测试

没有同名 storage 测试包；由上传全局并发、低空间、执行前复核与渲染容量测试覆盖。

```bash
mvn -B -ntp -pl backend -am test -Dtest=UploadGlobalCapacityIntegrationTest,UploadLowSpaceIntegrationTest,UploadSpaceRecheckIntegrationTest,RenderCapacityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试源码见[后端测试目录](../../../../../../test/java/com/agenticvideo/business)；集成测试需要 Docker，媒体测试另需宿主 FFmpeg／ffprobe。此处是相关回归命令，文档改动不执行应用测试。

## 9. 扩展与维护

新增需要写磁盘的任务时，接纳事务写入未来预算并在终态／清理路径释放，不能只调用 require 而不保存预留。修改估算应覆盖上传和渲染调用方；不要把该模块扩成对象存储客户端。

## 10. 相关文档

[根 README](../../../../../../../../README.md) · [后端模块地图](../../../../../../../README.md) · [领域术语](../../../../../../../../GLOSSARY.md) · [upload](../upload/README.md) · [render](../render/README.md) · [media](../media/README.md)

[issue-8.md](../../../../../../../../docs/verification/issue-8.md)
