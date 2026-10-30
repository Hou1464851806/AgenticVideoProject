# Naive UI 第四阶段实施记录

2026-10-30（Asia/Shanghai）。状态：第四阶段完成；对应当前工作区未提交改动。未部署或发起真实付费渲染。

## 实施

- 产物面板将方案内容、正式导出设置、低清预览和正式成片任务分区；方案、版本及输出名称、片段与字幕按现有数据展示，预览和导出的来源版本继续固定。
- 桌面产物面板提供指针拖拽及方向键/Home/End 调宽，范围为 300–680px，按窗口及工作区宽度限制，以保证对话区至少 400px；窗口变化时重新限制宽度，不持久化偏好。
- 960px 及以下工作区与产物展开为覆盖式面板；切换、选择项目或聊天、Escape 与遮罩关闭均不卸载产物面板或取消任务，关闭恢复焦点。窄屏面板打开时对话区不可交互，并限制键盘焦点。
- 修复第一阶段 Naive UI 主题容器多包一层导致长对话撑高而不能滚动的问题；主题提供器改为不生成额外容器。修复后消息滚动回归通过。

## 验证

- 使用当前前端 Vite 与受控接口执行 61 项相关 Playwright 场景：全部通过。范围包括第一至三阶段的当前有效浏览器基线、证据/上传/引用、第四阶段面板布局、制作生命周期与产物/用量。命令在 frontend 目录：`E2E_BASE_URL=http://127.0.0.1:8084 npx playwright test e2e/evidence-layout.spec.ts e2e/frontend-redesign-baseline.spec.ts e2e/naive-ui-stage1.spec.ts e2e/stage3-reference.spec.ts e2e/results-usage-workspace.spec.ts e2e/stage4-layout.spec.ts e2e/conversation-lifecycle.spec.ts e2e/production-workspace.spec.ts --grep-invert 'plain chat sends|narrow conversation keeps'`。
- 涵盖多输出草稿预览与整份确认、无需预览即可确认、正式导出单独请求、多输出/版本来源固定、历史草稿只读、独立停止、处理中禁止重复导出、失败及产物失效；面板收起后保留预览请求身份。
- 桌面键盘及鼠标调宽、窗口缩小时限宽，768px/390px 覆盖、Escape/焦点恢复和窄屏项目选择通过。产物截图：[1366×768](frontend-naive-ui-stage4/desktop.png)、[768×1024](frontend-naive-ui-stage4/tablet.png)、[390×844](frontend-naive-ui-stage4/mobile.png)。
- `npm run build` 通过，包含 vue-tsc；主 JS 531.49 kB（gzip 161.19 kB），Vite 保留超过 500 kB 的体积提示。
- 首轮完整回归有两项失败：长对话滚动故障已修复并复跑通过；新聊天素材名称测试仍使用第三阶段前的旧引用控件，已适配当前选择器并通过。窄屏用量旧测试按两个侧栏同时展开操作，已改为先关闭产物再打开工作区，三视口通过。

## 限制

排除的三项旧测试使用第三阶段已替换的引用控件，当前素材引用行为另由 `stage3-reference.spec.ts` 覆盖；这些旧用例没有计为通过。浏览器接口和渲染请求均为受控数据，真实后端、对象存储、媒体合成及付费模型未运行；本记录不代表真实端到端或音画验收。当前范围未运行 Java/Python 全量回归或部署。
