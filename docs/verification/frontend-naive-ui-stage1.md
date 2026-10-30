# Naive UI 第一阶段实施记录

2026-10-30（Asia/Shanghai）。状态：第一阶段完成；本记录对应当前工作区未提交改动。未部署或调用付费模型。

## 改动

- 安装并锁定 Naive UI 2.45.3，配置基础主题；登录输入及按钮、账户浮层和新建项目弹窗与按钮接入组件库。
- 限制旧按钮和输入样式对 Naive UI 的覆盖，包括原来作用于所有提交按钮的规则；旧页面仍沿用既有样式，后续阶段逐步替换。
- 修复新建项目弹窗焦点守卫：内容根节点使用组件库要求的 div；Escape、Tab 循环及关闭后的焦点恢复经浏览器测试。保留创建中禁用关闭与按钮的行为。

## 验收和验证

- UI-01：受控接口验证注册、登录、账户信息与退出，以及项目创建失败和成功；未连接真实认证服务。
- UI-02：检查当前接入的按钮、输入及提交样式，隔离旧全局规则；视觉截图与既有工作台基线通过。该结论仅针对第一阶段已接入控件，不代表后续所有 Naive UI 组件均已检查。
- UI-03：三视口验证弹窗焦点留在弹窗、Tab 循环、Escape 关闭、焦点返回触发按钮。
- UI-04：受控登录工作区弹窗在 1366×768、768×1024、390×844 截图，分别为 [桌面](frontend-naive-ui-stage1/desktop.png)、[平板](frontend-naive-ui-stage1/tablet.png)、[手机](frontend-naive-ui-stage1/mobile.png)；检查无横向溢出。改造前已有工作台记录见[上一轮基线](frontend-redesign-step1.md)，并非本次同一受控场景的逐像素对比。
- `E2E_BASE_URL=http://127.0.0.1:8081 npx playwright test e2e/naive-ui-stage1.spec.ts e2e/frontend-redesign-baseline.spec.ts`：5 项通过；Vite 提供当前前端，业务接口为受控响应，不依赖真实服务。初始焦点测试曾失败，修正弹窗根元素后重跑通过。
- `npm run build`：通过，包含 vue-tsc 与 Vite 构建。当前主 JS 409.75 kB（gzip 129.75 kB），CSS 40.44 kB（gzip 7.99 kB）。使用同一依赖与工具对 Git HEAD 前端构建，基线主 JS 179.50 kB（gzip 61.09 kB），CSS 40.23 kB（gzip 7.94 kB）；主 JS 增加 230.25 kB（gzip 68.66 kB）。这是引入组件库的体积代价，后续可在有实际需要时评估。

## 测试分类和未运行项

本阶段门槛为上述受控浏览器场景、当前三栏基线及前端构建，均为非付费；`frontend-redesign-baseline.spec.ts` 使用当前三栏并拦截接口。`fixtures/current-frontend.ts` 支持通过 `E2E_UI_ORIGIN` 连接既有 Java 部署；`media.spec.ts` 等真实服务测试依赖后端和本地基础设施，未作为本阶段门槛。旧导航或模型相关测试须在适配当前入口后判断是否纳入后续回归。未运行真实注册/登录、真实上传、模型调用或全量服务回归，不能由受控测试推断通过。
