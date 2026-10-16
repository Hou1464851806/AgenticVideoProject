# 前端重构阶段验收与本机部署

日期：2026-10-16（Asia/Shanghai）。本步起点 `4523c0a`，阶段审查基准 `82bf48d`（步骤 1～5 的全部差异）。依据[设计](../frontend-redesign-design.md)、[实施计划](../frontend-redesign-implementation-plan.md)及[步骤 5 交接](frontend-redesign-step5.md)。状态：步骤 6 完成，非付费自动验收与本机更新完成，人工连续音画及讲话字幕仍待用户试用验收。

## 步骤 6 实际改动与审查

按 implement、tdd、code-review 执行；测试沿用计划已确认的浏览器 UI／HTTP 边界，不新增内部实现测试或通用测试平台。Standards 和 Spec 分别由独立子代理审查完整阶段差异，首轮各 2 项 P2；修正后两轴均为 0 剩余发现。

- 跨主视图查看方案、引用作品时，若焦点位于即将隐藏的面板，在 DOM 更新后聚焦目标面板；主导航键盘操作继续由页签管理焦点。
- 删除成片的内联确认打开后聚焦“保留文件”，Escape 可取消，取消回到删除按钮；删除提交成功后聚焦导出状态区。确认对象和删除请求身份沿用原实现。
- 常驻聊天组件在新建／切换会话时重新读取素材名称，避免空项目导入素材后仍显示空引用列表或未知名称；素材范围仍由会话快照决定。
- 作品的低清预览绑定提交时的输出名称与固定版本，切换方案不会把旧预览标成新输出；历史预览从原版本解析名称。

以上 4 项均先通过公共 UI 测试复现失败，再修正通过；删除确认额外验证 Escape、Tab 和 Enter。制作、作品及导航模块 41 条通过，0 失败、0 跳过；随后只增强删除确认的键盘断言，单独 1 条通过，最终 64 条完整清单再次覆盖该断言。首轮阶段边界运行期间增加了焦点断言，该轮为 61 通过、1 失败；这次失败即上述跨视图焦点红例，不作为最终通过结果。

未改后端业务、Java/Python 契约、数据库迁移、模型、上传核心或公共事件协议；不扩大阶段业务能力。

## 验收矩阵与证据

| 场景／标准 | 验证边界与证据 | 结果 |
| --- | --- | --- |
| 账号与项目（R2-01～R2-04） | workspace-navigation：退出／到期、空项目、偏好、跨项目、浏览器历史、迟到响应与无权限；真实部署 projects 用例另验注册／登录／创建／隔离 | 通过（上述自动化边界） |
| 导入与素材（R3-01～R3-03） | assets-workflow：身份去重、同名、重选、过期、清理、部分失败；workspace-navigation：上传中切换、暂停恢复；Java 完整回归覆盖真实上传 API | 通过（上述自动化边界） |
| 查找与核查（R3-04～R3-06） | assets-workflow：原文／语义参数、无命中覆盖、原片定位／片段结束、同源签名续期、补查授权 | 通过（上述自动化边界） |
| 对话与澄清（R4-01～R4-03） | conversation-lifecycle、production-workspace：原文及引用、待澄清、独立问题、草稿及源片核查、新导入素材名 | 通过（上述自动化边界） |
| 草稿与历史（R4-04～R4-06） | 生命周期／制作：多输出选择、未确认预览、整体确认、修改与旧稿只读、历史来源、无自动导出 | 通过（上述自动化边界） |
| 故障与恢复（R4-07～R4-08、R5-03～R5-05） | 生命周期／制作／作品／导航：刷新、响应丢失幂等、签名续期、文件失效、独立停止、UNKNOWN、预算耗尽 | 通过（上述自动化边界） |
| 作品与文件（R5-01～R5-05） | results-usage-workspace + LocalMedia：固定输出／版本、720p／1080p 解码／播放时间推进／下载、删除与原片保留 | 通过（上述自动化边界） |
| 用量与维护（R5-06～R5-08） | results-usage-workspace、assets-workflow：未知费用、预留／待清理、预警保存、诊断白名单、明确维护且无自动模型写入 | 通过（上述自动化边界） |
| 可访问性与视觉（R2-02、R2-05、R4-02、R6-03） | 三视口实屏及无页面溢出断言；主页签键盘、跨视图焦点、删除确认键盘、用量开关焦点、中文组词、回看历史滚动 | 通过（上述自动化边界） |

实屏目录：[本阶段截图](frontend-redesign-stage/)。HTTP 受控场景与真实已有服务分别命名，不把 mock 当成真实服务。临时日志保存在忽略目录 `.scratch/asr-fixture/frontend-stage/`；Java 报告位于 `backend/target/surefire-reports/`，内嵌真实浏览器日志为 `backend/target/conversation-lifecycle-browser.log`。

### 费用边界复核

本机自动分析开启时，成功上传静音视频仍会由 `MediaProcessing` 抽帧，再由 `VisualAnalysis.initialize` 自动提交 VLM。步骤 1 将无音轨与无模型费用混同的分类已更正，步骤 3 增加历史结论修正说明。本轮未运行共享部署的 `uploads` 成功用例、`media` 的 local MQ 成功用例或 `analysis` 的静音成功用例；不延用历史“12 条均非付费”的说法。

成功媒体由分析默认关闭的隔离 LocalMedia 验证，分析执行模块使用本地受控供应商。部署后仅选不可解码 Buffer 续传 5 条、媒体校验拒绝 3 条及账号／项目 1 条；不改变用户部署的自动分析配置。费用分类另由 Spec 子代理按媒体校验顺序与自动入队源码复核，无剩余发现；历史实际费用未查证。

## 最终代码验证

Java/Python 全量及首轮前端 64 条回归对应的产品与测试差异：`git diff 4523c0a -- frontend/src frontend/e2e` 的 SHA-256 为 `2aecfdab62405a991049ec1f2fc37d729049abfb1297c1d8a5e0262134626b66`。初始四项修正及两轴复核在阶段全量之前完成；其后部署发现的账户菜单 CSS 修正及补跑单列于文末，不把前一轮全量视为最终 CSS 的验证。

阶段末执行一次完整 Java／Python 非付费回归及后端打包；前端运行步骤 1～5 维护的当前入口清单，不运行依赖付费 renderSeed 或退役链路的历史整个目录。Maven 串行，`RUN_LIVE_JAVA_ANALYSIS=0`、`lifecycle.model=false`。

```sh
npm --prefix frontend run build
E2E_BASE_URL=http://127.0.0.1:5173 E2E_REDESIGN_SCREENSHOTS=../docs/verification/frontend-redesign-stage npm --prefix frontend run test:e2e -- results-usage-workspace.spec.ts production-workspace.spec.ts conversation-lifecycle.spec.ts workspace-navigation.spec.ts frontend-redesign-baseline.spec.ts assets-workflow.spec.ts --grep-invert 'real existing'
RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp -Dlifecycle.browser=true -Dlifecycle.model=false verify
agent-service/.venv/bin/python -m pytest agent-service/tests -m 'not live' -q
agent-service/.venv/bin/python -m compileall -q agent-service/agent_service agent-service/tests agent-service/scripts
```

| 检查 | 最终结果 |
| --- | --- |
| 前端类型与生产构建 | 初始与菜单和费用 CSS 修正后的最终 build 均通过，包含 vue-tsc --noEmit；55 模块 |
| 当前入口 HTTP 完整清单及收尾补跑 | 菜单修正后串行 64/64 通过（素材 15、生命周期 5、受控基线 3、制作 15、作品／用量 15、导航 11）；最后仅窄屏用量 CSS 与夹具调整后，作品／用量模块再跑 15/15 通过，其余不受影响模块未重复；随后对话终态修正后制作 16 + 生命周期 5 再跑 21/21 通过。完整与补跑均 0 跳过 |
| Java 完整非付费回归与 JAR 打包 | BUILD SUCCESS，35 份本次报告：241 总数、237 通过、4 跳过、0 失败、0 错误；4 项均为显式付费 JavaAnalysisLiveTest，不算通过。耗时 16:53，Spring Boot JAR 重打包完成 |
| 内嵌真实媒体浏览器 | 1 条通过，0 失败、0 跳过（23.9s）；本次 Java verify 实际注入并运行，不是缺环境的 skip |
| Python 完整非付费回归 | Java 结束后串行重跑：213 通过，0 失败、0 跳过（34.71s）；首轮时序失败单列如下 |
| Python compileall | 通过 |
| 真实已有项目三视口 | 初始代理、菜单修正后及最终镜像均各 3 条通过，0 失败、0 跳过；最终直接使用已部署静态资源，9.9s，仅读取既有素材和聊天 |
| 最终实际部署冒烟 | 菜单修正后，账号／项目 1、不可解码续传 5、媒体校验拒绝 3，共 9 条全部通过，0 失败、0 跳过（54.8s）；包括实际手机退出。此后费用 CSS／终态文案未影响上述业务，未重复；最终镜像另以 18 张实屏及三视口键盘退出／终态检查验证 |
| 文档链接、差异、实屏审查 | 75 张 PNG，含三种目标视口、18 张最终实际部署视口图；几何／尺寸与代表实屏审查通过，183 个本地文档链接、代码块配对、git diff --check 均通过 |
| 真实付费模型 | 本轮未运行；Java 显式付费用例 4 条跳过，lifecycle.model=false，未将其算为通过 |
| 人工连续音画与讲话字幕 | 未验收，交由用户本机试用 |
| 历史退役链路浏览器整个目录 | 未运行，不作为当前阶段门槛 |

### 时序敏感测试的首轮失败

Python 首轮与 Java／浏览器并行执行，为 211 通过、2 失败：`test_active_cancellation_stops_new_model_calls` 在固定等待 150ms 后取消时，模型替身尚未启动；`test_stalled_heartbeat_cannot_keep_model_running_beyond_acknowledged_lease` 的 200ms 租约到期前模型替身也尚未启动。两者的实际失败均是预期模型启动次数 1、实际 0。

未修改 Python 产品或测试来放宽断言。随后两条单独复跑 2/2 通过（1.35s），所在两个文件 22/22 通过（10.68s）；这表明测试对并发负载下的启动时序敏感。Java 结束后串行全量重跑 213/213 通过，首轮不算通过。

## 本机更新与人工试用

本轮用户明确要求步骤 6 完成后更新本机部署，该授权覆盖本次部署；设计中“不自动触发部署”的默认边界不阻止用户明确要求的更新。阶段初轮完整自动回归通过后已构建并更新本机；部署发现菜单问题后完成 CSS 修正、前端回归并再次更新。最终业务镜像 `e8db826548e89`，Worker 镜像 `c51a5ba5dbc5` 保持，Worker tracing 源码哈希与本机一致。PostgreSQL／RabbitMQ／业务 API 健康，Qdrant、Silo、Worker 运行。最终首页、JS、CSS 与哈希 worker 四个文件逐字节匹配本机生产 dist。

首次更新前后（部署冒烟之前）用户／项目／素材数量分别为 1620／1716／1569，完全一致；数据库仍为 V57，无新迁移。私有 pg_dump 备份位于 `agent-service/.local/deploy-backups/before-frontend-redesign-stage.dump`（权限 600，pg_restore --list 验证可读，不入库）。更新只重建 business-api，不重建或删除数据卷；后续冒烟只向独立测试账号增加空项目和无效输入。部署前后语音、视觉、会话及查询四类模型调用表数量均未增加，不新增真实付费调用。

真实付费模型验收本轮不运行；人工连续观看、讲话原声／字幕质量由用户本机试用后确认。静音真实媒体只证明渲染、播放、下载与生命周期，不证明讲话字幕质量。

## 手动完整试用路线

1. 进入 [本机工作区](http://localhost:8080)，使用原账号重新登录，或创建新账号与项目。
2. 在“素材”导入视频；也可打开已有项目复用已分析素材。确认传输、媒体验证、语音与画面能力各自状态。
3. 查找原句或主题，展开筛选与覆盖说明，从命中回看原片，再返回结果；需要时明确授权补查。
4. 在“制作”新建聊天，先问答、核查引用，再用自然语言说明素材、输出数量、时长、横竖屏、原声与字幕要求。待澄清时在原输入框回答；可插入独立问题。
5. 核查当前草稿的输出及片段，提出修改，查看历史只读状态。桌面并看对话与预览，手机用“对话／预览”切换。
6. 点击“预览草稿”，多输出先选一条，观看试听；满意后“确认全部输出”。确认覆盖全部输出，形成只读版本，正式导出仍需单独操作。
7. 在“作品”选择方案／版本与输出，选择 720p 或 1080p 正式导出；播放、下载。需要删除时先核对名称再确认，原素材保留。
8. 查看“用量”中的估算、未知费用与预警；刷新页面核对恢复。各任务的停止入口分别操作，不随切换视图取消。

## 全量之后的部署发现与补跑

首轮部署后，当前入口 3 条真实已有项目基线通过；补充非付费冒烟 8 条通过、1 条失败。失败是 `projects.spec.ts` 手机退出：账户菜单换到页头左侧后，原先按菜单右边缘定位的 240px 弹层越过视口左边界，Playwright 无法点击“退出登录”。这是真实布局缺陷，不将其归因于测试耗时。

在现有导航 HTTP 用例上补 390px 下退出按钮完全在视口内的断言，先复现失败。修正将窄屏账户弹层定位到项目页头内的右侧，桌面继续按账户按钮定位。此后前端完整清单 64/64 与生产构建通过；菜单 CSS 经 Standards／Spec 两轴复核均为 0 剩余发现。当前真实部署 9/9、已有项目三视口 3/3 均已通过；Java/Python 产品、契约和数据库均未改变，按根规范不重复后端全量。补跑完成后记录最终代码实际通过的检查，最终界面截图以部署后的视口图为准。

补充实屏脚本首轮在已有大项目的真实存储核对尚未完成时，因默认 5 秒等待超时而退出。调整的是本地截图脚本的等待时间（60 秒），没有掩盖服务错误或修改产品加载逻辑；等待结束后实屏脚本成功采集 18 张视口图，最终三个视口的键盘 Enter／Tab 退出与已完成任务状态均通过；真实用量加载分别为 1384／1383／860ms。第二轮采集曾因连续自动登录触发一分钟认证限流，按现有规则等待后成功，未修改认证设置。

菜单修正后曾补跑 64 条，其中 63 通过、首项在 `beforeEach` 浏览器路由设置阶段超时（尚未进入 `page.goto` 或业务断言）。保留该次失败记录，随后停止并发构建与其他浏览器检查，串行重跑同一完整清单，64/64 通过（1.1m），0 失败、0 跳过。没有更改应用或放宽测试超时来隐藏此项。

最终产品与测试差异 SHA-256（同一 diff 命令）：`3baecbff417963fbf902e2a2357f485aaeb4f74a182ce65914cbfb095fcb6e2f`。全量之后只改手机菜单定位、窄屏费用跨列、对话终态的显示优先级，以及相应公共 UI 测试／长金额夹具与文档；初始真实媒体截图反映完整回归时的界面，最终布局以 `deployed-*` 视口图及 `controlled-account-*` 为准；全页长截图的固定／粘性导航拼接不作为几何证据。

实屏收尾另发现 390px 下真实费用 `¥2.67844952` 被拆成两行；只让 ≤480px 的费用摘要跨两列，不改变数值精度。三视口受控夹具补长金额，作品／用量完整模块 15/15 与最终构建通过；两轴复核均为 0 发现。该样式只影响用量摘要，账号／上传 9 条和其他模块不受影响，不重复后端或全部前端清单；最终已重新部署并采集三视口确认，手机长金额完整单行显示。

最后核查真实已有项目时，发现终态任务保留 `public_phase`，旧界面会把 SUCCEEDED 任务显示成“正在验证草稿”。按现行 ProjectRecovery 快照与数据库实际状态确认后，仅修改显示选择：只有 RUNNING 使用阶段文案，其他状态使用任务状态。成功终态新用例先红后绿，既有失败／停止用例补保留 phase 的断言，3 条定向通过；制作 16 + 生命周期 5 共 21 条模块回归全部通过（23.7s），最终类型／构建通过。两轴复核均 0 发现；未改快照、操作资格、停止／重试、Java/Python 或数据库，不重复不受影响的服务全量。

最终部署只保留现有服务与卷，`agent.v1`／`media.v1`／`analysis.v1` 均无 ready 或 unacknowledged 消息，消费者分别为 1／1／2。常用正文／次级文字与表面的对比度为 5.59～15.95:1，主操作白字 7.80:1，控件边界对白色 3.29:1；这是一组主要色值核对，不冒充完整辅助技术人工认证。所有已确认阶段阻塞项已修正；后续只整理文档、复制实屏及提交，无新增产品改动。

本机部署及定向复现命令（真实服务用例只选上文核对过的非付费输入；UI_ORIGIN 与部署同源，仅用于测试上下文的本地网络访问授权，不代理到 Vite）：

```sh
docker compose --env-file agent-service/.env.langfuse --env-file .env build business-api
docker compose --env-file agent-service/.env.langfuse --env-file .env up -d --no-deps --no-build --wait --wait-timeout 180 business-api
E2E_BASE_URL=http://localhost:8080 E2E_UI_ORIGIN=http://localhost:8080 npm --prefix frontend run test:e2e -- projects.spec.ts upload-resume.spec.ts media.spec.ts -g 'two browser|pause settles|pausing while|paused queued|persistent failure|refresh reselects|undecodable|independently rejects|over-duration'
E2E_BASE_URL=http://localhost:8080 E2E_REDESIGN_REAL=1 npm --prefix frontend run test:e2e -- frontend-redesign-baseline.spec.ts -g real
E2E_BASE_URL=http://127.0.0.1:5173 npm --prefix frontend run test:e2e -- results-usage-workspace.spec.ts
E2E_BASE_URL=http://127.0.0.1:5173 npm --prefix frontend run test:e2e -- production-workspace.spec.ts conversation-lifecycle.spec.ts
```

最终结论：**非付费自动验收完成，人工音画未验收**；阶段范围无剩余自动化阻塞，已更新本机供用户完整试用。
