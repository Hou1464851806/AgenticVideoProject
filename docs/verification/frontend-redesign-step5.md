# 前端重构步骤 5 验证记录

日期：2026-10-16。基准：步骤 4 提交 `e290e50`。沿用步骤 1 测试清单、HTTP 边界及隔离真实媒体入口，按 R5-01～R5-08 完成作品、用量与诊断分层。

## 实际改动

- 作品保留已保存方案入口，唯一版本直接打开，多版本保留选择；所选方案版本独立读取并用选择序号排除旧响应，不依赖后台轮询是否在途。已确认无导出记录显示“待导出”；已有确认不再展示确认按钮，历史未确认版本保留显式确认。
- 输出以标题、时长、画幅、实际字幕数量展示，片段与字幕按需展开；每次导出一条输出，提交前显示固定版本与清晰度。导出请求继续携带原 revision、confirmation、output 和幂等键；响应丢失后重放同一请求。同规格执行中禁止重复提交。
- 导出播放器标注实际输出、固定版本与规格，切换查看版本不改任务来源。播放签名按同一产物续签，保留播放位置；导出历史继续从现有接口恢复，不自动重新渲染。
- 文件状态与任务状态区分：成功记录不代表文件可用，删除中／已删除／不可用有独立反馈。删除先说明具体输出和后果，再明确确认；请求固定待删除产物身份，原素材和方案保留。下载不会发删除请求。
- 用量默认展示已用／预留／待清理及估算费用，未知费用单列且不当零；分类、估算依据和调用明细按需展开。预警保留保存与刷新恢复，明确提示不阻止执行。
- 项目次级“诊断”入口的完整可访问名称为“诊断与高级维护”。开启后按现有上下文显示制作、校验、任务及请求身份，并可前往素材索引维护；不创建第二套控制器，不自动分析／重建／付费。用量回执仅允许已知数值字段，不渲染完整供应商 JSON 或内部推理。

错误沿用现有公开 message，仅展示允许的业务字段，不将整个任务／供应商响应作为错误或诊断。不新增计费、权限或后端契约；无法从公开字段获得额外处理建议时保留服务真实原因，不编造统一网络错误。

## 范围与命令

`results-usage-workspace.spec.ts` 覆盖作品／用量公共 UI 与 HTTP，直接相邻为素材维护、对话引用、导航和生命周期。本步未改 Java/Python、上传核心、公共请求或事件，因此按计划运行局部及相邻回归、类型／构建、隔离真实媒体，不运行整个历史 Playwright 目录及 renderSeed 冷建。

```sh
# 仓库根目录；已有 Vite 5173 与 Java localhost:8080
E2E_BASE_URL=http://127.0.0.1:5173 E2E_REDESIGN_SCREENSHOTS=../docs/verification/frontend-redesign-step5 npm --prefix frontend run test:e2e -- results-usage-workspace.spec.ts production-workspace.spec.ts conversation-lifecycle.spec.ts workspace-navigation.spec.ts frontend-redesign-baseline.spec.ts assets-workflow.spec.ts --grep-invert 'real existing'
E2E_BASE_URL=http://127.0.0.1:5173 npm --prefix frontend run test:e2e -- results-usage-workspace.spec.ts
npm --prefix frontend run build
RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp -pl backend '-Dtest=LocalMediaIntegrationTest#unacceptedAndSavedSilentDraftsRenderThroughMqWithTheirOwnSources' -Dlifecycle.browser=true -Dlifecycle.model=false test
E2E_BASE_URL=http://localhost:8080 E2E_UI_ORIGIN=http://127.0.0.1:5173 E2E_REDESIGN_REAL=1 E2E_REDESIGN_SCREENSHOTS=../docs/verification/frontend-redesign-step5 npm --prefix frontend run test:e2e -- frontend-redesign-baseline.spec.ts -g real
```

## 实际结果

| 检查 | 最终结果 |
| --- | --- |
| 模块与相邻 HTTP 回归 | 最终产品代码的 61 条完整相关清单通过，0 失败、0 跳过：作品／用量 13、制作 14、素材 15、生命周期 5、导航 11、基线 3 |
| 补充正式成片签名回归 | 随后仅新增测试、未改产品代码；作品／用量模块完整 14 条通过，包含同产物签名续期且零重新导出请求 |
| 真实已有项目三视口 | 最终页头调整后再次执行，3 条通过，0 失败、0 跳过；仅读取、不调用模型 |
| 隔离 Java／MQ／存储／媒体 | 指定 LocalMedia Java 测试和内嵌浏览器通过，0 失败、0 跳过。最终增强验收实际验证 360p 草稿、冻结版本、720p／1080p 解码及播放时间推进、两种规格下载、成功文件删除（removedBytes > 0）、原素材 Range 访问仍为 206，以及外部丢失文件删除／刷新恢复 |
| 类型与构建 | 最终独立 vue-tsc --noEmit 和生产 build 均通过，55 模块 |
| 实屏与静态检查 | 53 张 PNG，三视口主内容无横向溢出；检查作品／用量、桌面／手机与真实媒体；103 个本地文档链接、图片尺寸和 git diff --check 通过 |
| Standards 复核 | 0 剩余发现 |
| Spec 复核 | 0 剩余发现 |

61 条完成后仅补充同产品代码的签名测试、增强真实媒体下载／删除断言和文档；未将前一轮结果冒充新增断言的结果。真实媒体最后一轮使用最终构建，报告来自 backend/target/surefire-reports 和 conversation-lifecycle-browser.log（本地临时报告不入库）。

## 审查与修正

以 git diff --cached e290e50 为固定比较，按 code-review 技能由两轴独立审查。Standards 首轮 2 项 P2，Spec 首轮 2 项 P2，均为同两处问题：

1. 新增“待导出”条件改变相邻 v-else 的绑定，导致已确认且有历史时重复要求确认。确认按钮改为显式 !confirmationId，并新增已有导出历史的断言。
2. 选择单版本依赖可能跳过的后台 refresh，导致轮询在途时只有版本列表、没有详情。选择操作独立读取版本，用 epoch 判旧，并新增故意延迟轮询的回归。

两轴复核均无剩余发现。受影响的 5 条定向用例先通过，再运行相关完整清单。首次 58 条相邻回归只有素材维护入口因文本重复而严格定位失败（其余 57 条通过），改为完整名称的项目诊断按钮；未为旧选择器保留错误入口。

首轮真实媒体已完成草稿、720p／1080p 播放，但在选择历史单版本方案时复现上述轮询问题而失败；修复后使用最终构建重新执行。手机实屏发现新增长诊断文案挤压页头，缩短为“诊断”并保留完整可访问名称，补跑相关清单及真实三视口；类型和构建重跑。

[步骤实屏目录](frontend-redesign-step5/)包含三视口作品／用量和相邻基线；额外导出长截图含固定导航在滚动位置的拼接伪影，未作为排版证据入库；实际导出解码／播放／下载由媒体断言验证。隔离媒体图由本次真实运行复制，不沿用步骤 4 结果。静音夹具只能证明媒体解码和任务链路。阶段完整 Java／Python 非付费回归、后端打包属于步骤 6，尚未执行；付费模型、人工音画、部署未运行。
