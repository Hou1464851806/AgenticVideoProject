# Naive UI 第五阶段与最终验收

2026-10-30（Asia/Shanghai）。状态：前端实施与受控浏览器验收完成，真实服务和人工音画验收未运行。本记录对应提交前的工作区内容。

## 实施

用量弹窗将关键指标、存储分类、预警设置和调用记录分区；调用记录表格展示费用和可展开回执，保留诊断信息边界与阈值提交契约。删除弹窗对文字和表格的过小字号覆盖；表格在窄屏内部横向滚动。

## 最终验证

- `npm run build`：通过，包含 Vue 类型检查和 Vite 构建；主 JS 531.84 kB，gzip 161.28 kB，Vite 提示代码块超过 500 kB。
- `E2E_BASE_URL=http://127.0.0.1:8085 npx playwright test e2e/evidence-layout.spec.ts e2e/frontend-redesign-baseline.spec.ts e2e/naive-ui-stage1.spec.ts e2e/stage3-reference.spec.ts e2e/results-usage-workspace.spec.ts e2e/stage4-layout.spec.ts e2e/conversation-lifecycle.spec.ts e2e/production-workspace.spec.ts --grep-invert 'plain chat sends|narrow conversation keeps'`：61 项通过、0 失败。受控接口；视频定位使用本地视频。该范围覆盖 UI-01～UI-21 对应的主要行为，详见此前阶段记录。
- 用量相关测试覆盖估算费用未知、存储分类、阈值保存、调用记录和诊断回执；三视口用量场景检查页面溢出，并监听浏览器控制台错误及未捕获异常，均通过。浏览器错误检查仅覆盖该三视口场景，不宣称所有场景均已监听。
- 用量截图：概览 [桌面](frontend-naive-ui-stage5/controlled-usage-summary-1366x768.png)、[平板](frontend-naive-ui-stage5/controlled-usage-summary-768x1024.png)、[手机](frontend-naive-ui-stage5/controlled-usage-summary-390x844.png)；明细 [桌面](frontend-naive-ui-stage5/controlled-usage-detail-1366x768.png)、[平板](frontend-naive-ui-stage5/controlled-usage-detail-768x1024.png)、[手机](frontend-naive-ui-stage5/controlled-usage-detail-390x844.png)。其他区域截图与验证见[第一阶段](frontend-naive-ui-stage1.md)、[第二阶段](frontend-naive-ui-stage2.md)、[第三阶段](frontend-naive-ui-stage3.md)、[第四阶段](frontend-naive-ui-stage4.md)。
- 最终构建与回归之后只更新验收文档与索引，未再改应用代码。

## 跳过与限制

三项旧测试依赖已替换的引用控件，未计入 61 项；当前引用行为由专用受控测试覆盖。真实认证、Java、对象存储、分片恢复、媒体合成、真实画面和付费模型未运行，受控测试不能证明这些服务端到端通过。第二阶段未模拟刷新后证据总数缩减的页码收敛；第三阶段未模拟全部扩大补查生命周期或验证真实观察帧内容。未运行 Java/Python 全量回归，原因是本轮未改其代码与契约；未部署或做人工音画验收。
