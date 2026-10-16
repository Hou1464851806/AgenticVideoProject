# 前端重构步骤 2：基础视觉与项目导航

日期：2026-10-16（Asia/Shanghai）。基准：`e14f4f1`（步骤 1）。范围依据[设计](../frontend-redesign-design.md)、[实施计划步骤 2](../frontend-redesign-implementation-plan.md#步骤-2-调整基础视觉与项目导航)和[步骤 1 基线及有效测试清单](frontend-redesign-step1.md)。这是完整重构阶段的内部步骤，阶段全量仍在步骤 6 执行。

## 实现与验收

| 标准 | 实际实现与证据 |
| --- | --- |
| R2-01 | 登录／注册表单沿用认证逻辑；项目页头合并品牌、返回、项目名称、用量和账户。退出收在账户菜单；真实双账号注册／登录／创建／打开／无权限／退出通过。过期呈现另用受控 401 验证。 |
| R2-02 | 全视口显示素材／制作／作品，当前项有文字与边线标识；方向键、Home／End、roving tabindex 和跳至主内容入口可用。未激活面板用 v-show 隐藏，不能被聚焦，业务组件保留。 |
| R2-03 | 素材列表首次加载后恢复按账号、项目隔离的视图偏好，不重复查询素材；空项目与非法偏好默认素材。存储不可用时显示提示，仍可切换；先经项目接口授权，无权项目不挂载工作区。 |
| R2-04 | 同项目保留输入、引用、上传队列及已选文件；受控分片上传切换后仍可暂停／继续且只初始化一次。按项目给工作区整体设 key；切换、回列表和退出移除旧面板并清空选中对象／播放信息。请求序号阻止延迟项目响应覆盖浏览器返回的目标；返回／前进与 URL 一致。 |
| R2-05 | 基础样式迁入 style.css：中性色面、系统中文字体、普通／主／次／文字／危险按钮、焦点和减少动态效果规则。草稿确认与删除按钮仅补样式类，不改操作逻辑。三个视口覆盖素材、制作、草稿、作品及用量，检查无页面横向溢出；用量表格保留局部滚动。 |

主导航随滚动保持可达；手机页头允许项目名称独占一行，主视图沿用同一份业务组件。用量使用非模态侧面板，关闭／Escape 回到用量按钮，表单及业务逻辑保留。

本步继续保留原有业务面板；原素材播放器暂在作品面板，后续步骤 3／4 再处理原片回看归属、制作双栏及窄屏对话／预览。素材条目去重、草稿内部标识和作品内部重排仍属于步骤 3～5，不提前宣称完成。

## 环境、命令与回归范围

基础 CSS 影响全部面板，导航影响组件存活和项目身份，故选择：新增工作区导航边界文件、完整现行生命周期 HTTP 文件、三视口基线与用量冒烟，以及真实项目认证／隔离用例。未修改 api.ts、认证会话、上传传输、SSE 或后端契约，不扩大到 Java/Python 服务全量。

Vite：`npm --prefix frontend run dev -- --host 127.0.0.1`。受控边界：`http://127.0.0.1:5173`。真实 Java 服务：`http://localhost:8080`。

现有部署的可信 Origin 不接受 5173 的真实登录；为了测试当前源码且不部署／重建服务，`fixtures/current-frontend.ts` 在显式设置 `E2E_UI_ORIGIN` 时只将页面和静态资源取自 Vite，浏览器保持部署 Origin，所有 `/api/` 请求原样直达真实服务。真实项目文件用新建账号；实屏复用步骤 1 的已有 preview-seed，不运行 renderSeed 的冷建回退、不上传讲话素材、不触发模型。

```sh
# frontend/ 内：开发时先单条，再对应文件
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e -- workspace-navigation.spec.ts -g 'view switches preserve'
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e -- workspace-navigation.spec.ts conversation-lifecycle.spec.ts
npm exec -- vue-tsc --noEmit
# 最终受控回归，明确排除需要真实环境的 real 分支
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e -- workspace-navigation.spec.ts conversation-lifecycle.spec.ts frontend-redesign-baseline.spec.ts --grep-invert 'real existing'
# 当前源码 + 现有真实 Java 服务，无服务重建
E2E_BASE_URL=http://localhost:8080 E2E_UI_ORIGIN=http://127.0.0.1:5173 npm run test:e2e -- projects.spec.ts
E2E_BASE_URL=http://localhost:8080 E2E_UI_ORIGIN=http://127.0.0.1:5173 E2E_REDESIGN_REAL=1 npm run test:e2e -- frontend-redesign-baseline.spec.ts -g real
npm run build
```

## 执行结果与审查

| 检查 | 最终结果 |
| --- | --- |
| 当前导航与恢复 HTTP 边界 | 通过 11 条：输入／引用、上传暂停续传、有效及非法偏好、账号隔离、空项目、无权限、迟到项目响应、返回／前进、退出／过期、存储不可用、三视口键盘及长名称布局 |
| 现行 conversation-lifecycle HTTP 文件 | 通过 5 条：原文／引用、双输出草稿、独立停止、同产物续签、失效后显式重试 |
| 受控基线 | 通过 3 条，三视口均覆盖当前／历史草稿、只读、UNKNOWN、预算耗尽和语音成功／画面失败；与以上两项同次共 19 条，0 失败、0 跳过 |
| 真实 Java 项目与基线 | 通过 4 条：双账号注册／登录／创建项目／隔离／退出，加三个视口已有项目；0 失败、0 跳过。最后使用 projects + baseline 的 -g 'real existing\|two browser' 合并执行 |
| 前端类型与生产构建 | 单独 vue-tsc --noEmit 通过；最终 npm run build 通过（内含类型检查，51 个模块） |
| 三视口视觉与静态检查 | 27 张截图另存，所有场景断言页面无横向溢出；代表实屏检查含登录、列表、素材、制作／草稿、作品和用量。100 个本地 Markdown 链接、截图数量及宽度和 git diff --check 通过 |
| Standards 审查 | 0 项发现。独立代理按根规范及代码异味基线检查暂存差异，无待修正问题 |
| Spec 审查 | 0 项发现。独立代理逐项核对 R2-01～R2-05，无缺失、错误实现或范围扩张 |

审查固定点为步骤 1 的 e14f4f1；以 git diff --cached e14f4f1 审查当前待提交差异。审查后未修改产品逻辑；最终 19 条受控回归、4 条真实服务回归与构建均在最终产品源码上执行。此后仅整理验证记录和测试缩进，不沿用旧阶段全量结论。未运行项见下文，未将其计为通过或跳过。

开发过程失败记录：首条导航用例在旧界面找不到“制作”而失败（先红后绿）；随后存储失败提示用例使用单一 role=status 定位器，与 SSE 恢复提示冲突，已收窄到对应文案，单条补跑通过。没有为这两项失败修改业务协议或服务配置。

隔离真实媒体 live 与显式付费 model 文件只适配新导航入口、跨视图操作前的定位；本步不运行这两个文件，不把缺少注入环境的 skip 计为通过。Java/Python 完整非付费回归、后端打包、真实模型、人工音画和部署均未运行，按计划分别留步骤 4～6或单独验收。

## 实屏证据

保留步骤 1 的 24 张原图；本步另存到 [frontend-redesign-step2](frontend-redesign-step2/)。三个视口为 1366×768、768×1024、390×844，每个视口包含：

- real-login、real-projects、real-assets、real-conversation、real-results：现有真实 Java 数据与当前前端源码。
- controlled-draft-history-unknown、controlled-readonly-budget、controlled-partial-analysis：沿用步骤 1 的确定性 HTTP 状态。
- controlled-usage：用量侧面板与未知价格提示，使用受控数据。

示例：[桌面作品](frontend-redesign-step2/real-results-1366x768.png)、[手机素材](frontend-redesign-step2/real-assets-390x844.png)、[手机制作](frontend-redesign-step2/controlled-draft-history-unknown-390x844.png)、[平板用量](frontend-redesign-step2/controlled-usage-768x1024.png)。截图展示不等于真实播放解码或人工音画验收。
