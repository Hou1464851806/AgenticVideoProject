# Java 工具 M1：第一阶段实施记录

日期：2026-10-14。依据交接文档和 [已确认的最终设计](../java-tools-m1-design.md)开始实施。起始工作区 `git status --short` 为空，未覆盖用户未提交改动。本文记录首批重构及阶段验证，完整 M1 尚未完成。

## 已落地

- 根 Maven 聚合入口仅包含 `backend`；业务源码、测试和迁移进入标准 `backend/src`，Dockerfile 和 Maven 文件位于 backend 根目录。
- 媒体操作、受控 FFmpeg 编译、真实 PTS 抽帧和工作目录进入单个 Spring Boot 应用；删除独立媒体应用、HTTP 客户端、执行控制端点、内存执行线程池与 Worker 心跳。
- 媒体消费者并发 1、prefetch 1，直接执行阶段，业务结果或持久重试意图保存后才 ACK。任务状态、执行代次、取消和进程内活跃集合控制幂等与发布资格。
- Outbox 使用 mandatory 持久投递，确认且没有未路由退回才发布成功；broker 故障保留意图，按 2–60 秒退避。安全媒体失败最多三次，经 Outbox/MQ 重试，最终失败保留数据库原因并进入死信。
- 废弃本机代次经 Outbox/MQ 恢复，不再 HTTP 查询 Worker 或依赖执行租约。既有方案冻结、预览、版本确认、导出与产物发布规则复用。
- 部署默认禁用旧 HTTP Worker 协议、旧 Agent 任务接纳及按需细读。模型分析迁写完成前关闭其任务接纳和派发，语音/视觉阶段如实显示未开始，不新建无人消费的任务。
- 维护 backend/README 和根启动说明。旧模型脚本明确返回 `UNVERIFIED`/退出码 2，不再以 Python 测试跳过冒充 M1 验收。

## 验证入口与边界

`LocalMediaIntegrationTest` 使用真实 PostgreSQL、Silo、RabbitMQ 和宿主 FFmpeg。已运行的检查包括：

| 检查 | 结果 |
| --- | --- |
| 从分片上传入口执行媒体准备 | 通过；真实探测、全音轨准备与稀疏抽帧 |
| labelled-speech 时间映射 | 通过；派生音轨偏移 1436ms，抽帧覆盖仍为 complete=false |
| 完成后的重复消息 | 通过；只有一次尝试与完成回执 |
| 本机凭据及旧入口 | 通过；本机代次不产生 Worker token；旧 Worker 返回 410，旧 Agent 返回 503且不接纳任务 |
| 真实预览与确认后导出 | 通过；生成 640×360 预览及 1280×720 导出，H.264/AAC、时长与 AVAILABLE 产物状态有效 |
| 临时媒体失败 | 通过；三次有限尝试，数据库失败原因及真实死信可读取 |
| 取消与迟到结果 | 通过；取消后的任务不重新领取，失效结果被拒绝 |
| broker stop_app/start_app | 通过；暂停时数据库任务/Outbox 保留，无执行尝试，恢复后继续消费 |
| 废弃代次恢复 | 通过；核对后创建新的 MQ 执行意图，废弃代次不能发布 |

`OutboxPublishingIntegrationTest` 删除真实工作队列，验证未路由消息即使得到 confirm 也不会标为 PUBLISHED；重建队列后同一 Outbox 意图成功送达持久消息。已通过。

命令在仓库根目录执行：

```sh
mvn -B -ntp -pl backend -am test \
  -Dtest=LocalMediaIntegrationTest,OutboxPublishingIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp verify
```

历史契约测试显式开启 legacy 配置，保留上传、权限、证据、方案、确认和产物业务规则回归。它们不意味着新模型执行或旧 Worker 部署可用。测试类结束后关闭 Spring 上下文，避免基础设施容器销毁后其定时器继续查询。

## 回归期间发现并修复的问题

上传残留清理接口分开读取预留字节与清理状态，清理提交发生在两次读取之间时，可能报告 `CLEANED` 而字节仍为之前的 20。整轮回归及单独运行超额分片用例均复现；现改为一次 JOIN 查询取得相同数据库快照，原失败用例已独立通过。没有放宽清理断言或增加超时时间。

## 尚未验证与后续顺序

尚未进行 Java 百炼 ASR/VLM/Embedding 真正模型分析、完整索引生成、四工具跨语言契约、范围快照与稳定分页、Python JavaToolExecutor、真实 DeepSeek 问答及补答/恢复验收。这些按最终设计的第二至第五阶段推进。旧协议代码和测试在迁入其真实使用方后再删除。

当前恢复验证模拟遗留的本机运行代次，并实际经 MQ 继续；尚未进行新容器 SIGKILL 后重启的最终故障验收。完成后 ACK 前强制断连、积压下并发实测、中文字幕画面及音画人工核查也不能由本记录中的重复消息与媒体规格检查替代。

Dockerfile/Compose 已同步新结构，Compose 配置校验通过。首批镜像构建三次尝试未完成：基础镜像下载发生 EOF，首次长时间等待后中止；后续重试在 Docker Hub token 请求 EOF 后失败。用户检查网络设置后再次授权部署验证，后续成功结果见下节。

宿主 Maven 构建及测试中的单体 Spring Boot 启动已验证，完整 `mvn -B -ntp -pl backend -am verify` 回归为 131/131 通过、0 跳过（23:02:48，Asia/Shanghai）。最后收紧媒体执行类型后，再运行 `verify` 的媒体执行与 Outbox 专项，6/6 通过并重新打包最终源码（23:04:45）。这些阶段验证不等于完整 M1 验收。

## 容器部署补验（2026-10-14 23:49，Asia/Shanghai）

网络恢复后的首轮已成功构建前端和 Maven JAR，但 apt 在 LLVM 大包处长期没有进展，主动中止。Dockerfile 为 apt 添加 30 秒连接超时和三次重试，保留原有软件包版本；下一轮完整镜像构建成功。

执行入口：

```sh
docker compose build business-api
docker compose up -d --wait --wait-timeout 120 postgres rabbitmq silo qdrant
docker compose up -d --no-build --wait --wait-timeout 120 business-api
cd frontend
npx playwright test e2e/projects.spec.ts e2e/media.spec.ts \
  --grep 'two browser accounts|local MQ consumer' --reporter=line
```

- 新业务镜像构建、单体应用启动与健康检查通过；PostgreSQL/RabbitMQ 健康，Silo/Qdrant 运行。原有数据卷保留，旧 Agent/媒体 Worker 容器保持停止。
- 现有数据库从 V48 成功迁移到 V49，Flyway 校验 49 项迁移；应用约 6.7 秒启动完成，启动日志没有应用 ERROR/Exception。Flyway 仍提示其已测试的 PostgreSQL 版本最高为 17；本次 PostgreSQL 18.6 的迁移实际通过，不代表消除了该兼容提示。
- 业务容器 UID/GID 10001，`/work` 可写，FFmpeg 为 `6.1.1-3ubuntu5`，已安装并能匹配 `Noto Sans CJK SC` 中文字体。
- `media.v1` 消费者为 1，`analysis.v1` 消费者为 0，符合当前阶段配置。浏览器新上传的 `MEDIA_PREPARE` 在数据库为 `SUCCEEDED`，代次 `local_execution=true`、`executor_id=local`、Worker token hash 长度为 0。
- 两项 Chrome 浏览器测试均通过（15 秒）：私有项目与窄屏各区域可达；真实上传、MQ 媒体验证、授权播放、Range 206 读取、播放定位、过期签名续取及跨账号访问拒绝。
- 验证后媒体工作队列消息数为 0；原有 11 条媒体死信保留。新应用和四个基础服务保持运行，入口为 `http://localhost:8080`。

本次只补齐构建、启动和上述冒烟验证。未额外运行付费模型调用，也未把它替代前文尚未完成的故障、渲染人工核查及完整 M1 验收。
