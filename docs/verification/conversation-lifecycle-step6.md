# 对话生命周期步骤 6：浏览器聊天、草稿与方案衔接

日期：2026-10-16（Asia/Shanghai）。实施基准 `0ee7805aeae7ee4526d96e5caaf288241e13aedc`，当前分支 `main`，起始工作区干净。依据[第 5 步交接](conversation-lifecycle-step5.md)与[完整设计](../conversation-lifecycle-browser-design.md)，本次范围是七步顺序的第 6 步；第 7 步阶段验收另行执行。

## 实施边界

- ConversationPanel 使用现有新版 `/turns` 公共入口，仅发送原文与明确引用；移除当前浏览器入口的旧模式、生成表单及旧补答接口。新聊天初始化期间禁止创建，避免恢复会话与创建竞争；素材引用、草稿引用、问题引用与方案引用仍经 Java 鉴权。正文和引用都相同才重放原幂等键，修正任一项生成新操作身份。
- 从一致性快照恢复当前制作、条件、编辑代次、结构化草稿与 Java 操作资格。当前草稿早于最近消息窗口时仍独立恢复；普通问答、讨论、待回答问题和媒体请求分别展示。关联补答与独立问题共用原输入框，新制作仍由 Agent 理解后绑定。
- DraftCard 只负责所见草稿的展示、整体确认及单输出预览。绑定 message／validation／generation／两种 hash，按钮使用 Java 当前资格；多输出在点击预览后选择一条，确认一次接受全部输出。确认和预览的响应丢失可按原操作身份重放；不提交客户端片段内容、不创建隐藏版本。
- 独立预览列表恢复所有已接纳请求，状态与 Agent 分离。聊天停止仅取消选定 Agent 或挂起等待，各预览有自己的取消／重试。媒体没有 conversationId 的任务状态事件按已知 taskId 刷新快照。历史草稿只读，历史预览标记原草稿来源；已经播放的 A 不因当前草稿 B 而重定向。
- ArtifactPlayer 在聊天内按产物申请播放授权，地址失效时只续签同一产物，并保留播放位置。文件经服务端确认失效后，失败通知父级刷新快照；预览请求投影 artifactState，当前草稿才可显式重试 UNAVAILABLE／REMOVED。任意网络失败或普通授权续期不自动重渲染。
- 方案区保留现有方案／版本读取、原素材核查、正式渲染、播放、下载与导出记录恢复。草稿接受后出现方案引用与列表项，用户自行选择查看；新方案不自动切换正在查看对象。已确认版本可直接导出，多输出先点击入口再选一条；导出响应丢失复用原键，成功后的下一次明确导出可用新键。
- Java 必要接缝只有公开快照投影新版失败原因／recoverable，以及渲染请求投影已持久化产物状态。未改 Agent 生命周期、Python、MQ 协议、数据库迁移、模型契约或 FFmpeg 执行。可恢复中断给显式重试，UNKNOWN／预算耗尽没有强制重付入口；实际接纳仍由后端核验。

## 测试范围与结果

测试接缝沿用设计已确认的浏览器与公共 Java HTTP。开发先验证单行为，再扩大到前端模块与 Java 会话／媒体直接调用模块；本次不是完整阶段交付，不以退役表单历史浏览器套件或全阶段 Java／Python 回归作为默认门槛。

开发红→绿：旧 context 请求被拒绝；多输出规范化 `output_key` 选择；快照缺少新版 failure；审查复现正文不变、只修正引用仍重放旧请求；成功产物缺少状态投影与显式失效重试。测试修正与业务修正分别记录，失败运行不计入通过。

| 检查 | 最终结果 |
| --- | --- |
| 前端类型检查与生产构建 | 最终 `vue-tsc --noEmit` 与 Vite 生产构建通过 |
| 浏览器 HTTP 边界回归 | 5 项通过：普通聊天／刷新与修正引用、窗口外多输出草稿／整体确认、独立停止与历史只读、签名续期、确认文件失效后的显式重试 |
| 真实 Java／MQ／FFmpeg／存储浏览器回归 | 最终扩大回归中 1 项通过（同时包含在 LocalMedia 全部 6 项）；增强实际播放进度断言后专项 1 项再次通过，0 失败／错误／跳过。预览 360×640、正式导出 720×1280，均实际播放推进，下载成功；桌面／390px 手机截图检查通过 |
| Java 当前及直接受影响模块回归 | 6 类 65 项通过，0 失败／错误／跳过；包含 Agent 接纳、会话快照／SSE、草稿／版本预览、内部 CLI 预览、正式导出和实际媒体消费者。下述既有导出文件丢失场景因断言缺口不能视为验收通过 |
| 差异与文档链接 | 3 份文档 73 个本地链接可解析；`git diff --check` 通过 |

真实浏览器验收复用 LocalMediaIntegrationTest 的隔离 PostgreSQL、RabbitMQ、对象存储与已分析静音素材，加载当前前端生产构建。Node 测试驱动通过独立 Worker HTTP 客户端提供受控公开理解／结果与工具读取／Java 校验；内部凭据不进入页面。它验证前端与真实业务、持久化及媒体链路，不冒充 Python Worker 或真实模型意图／质量验收。

首次浏览器运行因隔离端口与配置 Origin 不一致返回 403，已只修正测试环境；后续运行暴露浏览器 Cookie 与内部调用共用客户端的夹具干扰，改为独立客户端后重新验证。测试对创建／重试响应等待实际完成后再使用身份。收尾条件展示的一处模板语法错误由类型检查发现并修正；最终构建通过。随后一次边界浏览器首项被开发服务器遗留的 HMR 错误遮罩阻挡，重启开发服务器后最终 5 项全部通过，该失败不计通过。开发时一次新 Maven 调用在前一次结束前误启动，发现后立即终止新调用，后续串行执行；中止调用不计通过。

未运行：第 7 步 Java／Python 全阶段完整非付费回归及后端打包、真实付费模型、Python Worker 整体浏览器模型验收、人工完整观看试听。自动媒体元数据／下载／播放器解码与人工观看分别报告。不部署业务实例、不改本机业务数据库；数据库与媒体写入只在 Testcontainers 隔离环境。

## 回归中发现的既有问题

正式导出对象被外部删除时，[ExportArtifacts.read](../../backend/src/main/java/com/agenticvideo/business/render/ExportArtifacts.java) 的 `Long bytes=... ? 0L : storage.objectSize(...)` 会对空值拆箱，GET `/artifacts/{id}` 返回 500。既有 [ExportApiIntegrationTest](../../backend/src/test/java/com/agenticvideo/business/render/ExportApiIntegrationTest.java) 缺少该请求的 HTTP 状态断言，错误响应被 `storedBytes.asLong()` 缺省值误判为 0。本次实际日志复现了该问题，因此测试进程通过不代表“正式导出对象丢失”场景通过。

独立 Spec 复核确认：该产物读取与 PlanPanel 恢复调用均在基准已有；本次没有引入或改变它们。按 AGENTS.md 的最小修改范围保留，交第 7 步同时修复空值拆箱、补 HTTP 状态断言并做对象丢失后的页面恢复验收。当前正常正式导出、播放器解码、下载和刷新恢复已独立验证。草稿预览失效重试属于本步新增入口，已在本步修复并验证，两者不混同。

多类集成测试关闭隔离容器后，缓存 Spring 上下文的既有清理定时任务仍记录连接失败；按实际 Surefire 测试结果单独统计，不把日志中的受控故障或清理异常写成业务验收通过。

65 项通过后仅增强真实浏览器测试，要求预览和正式成片调用 `play()` 后时间实际推进；应用实现没有再改动，按分层约定补跑该媒体／浏览器专项通过。最终 65 项结果及专项补跑分别保留，不把后一次单项报告冒充再次 65 项全跑。

## Standards

以固定基准审查全部变更和新增文件。独立规范审查及收尾增量复核均无剩余实质问题；草稿操作、产物播放、会话快照与版本渲染保持各自职责，未新增通用编排框架。静态审查不等于运行验证。

## Spec

独立设计审查发现两项 P2：发送失败后仅修正引用仍复用旧输入；成功预览文件确认失效后缺少显式重试入口。均已通过红测试复现、修复并独立复核，无剩余已确认实质偏差。

## 复现与第 7 步交接

```sh
npm --prefix frontend run build
# 另一个终端启动 Vite 后运行 HTTP 边界浏览器回归
npm --prefix frontend run dev -- --host 127.0.0.1
E2E_BASE_URL=http://127.0.0.1:5173 npm --prefix frontend run test:e2e -- conversation-lifecycle.spec.ts
# Maven 串行运行；先构建前端，真实浏览器由 Java 隔离夹具启动
mvn -q -pl backend -Dtest=LocalMediaIntegrationTest#unacceptedAndSavedSilentDraftsRenderThroughMqWithTheirOwnSources -Dlifecycle.browser=true test
# 最终扩大回归（包括上述真实浏览器和 LocalMedia 全部用例）
mvn -q -pl backend '-Dtest=AgentRunsApiIntegrationTest,ConversationRecoveryApiIntegrationTest,PreviewApiIntegrationTest,InternalPreviewApiIntegrationTest,ExportApiIntegrationTest,LocalMediaIntegrationTest' -Dlifecycle.browser=true test
```

第 7 步应对最终代码完成阶段验收矩阵与全阶段非付费检查，并按计划单独安排真实模型及人工音画／字幕观看。真实浏览器入口为新聊天和本步生命周期套件，不能将旧模式表单／旧 Agent 协议的历史测试直接作为验收门槛。当前制作资格、预览历史和确认后的方案都从 Java 恢复；无需打开 legacy 开关。
