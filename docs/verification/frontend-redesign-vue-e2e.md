# 前端重设计 Vue e2e 迁移记录

日期：2026-10-20（Asia/Shanghai）。对象：新版三栏 Vue 工作台（`frontend/src`）的当前 UI 门槛。应用部署于 `http://localhost:8080`，容器 `business-api` 健康。

## 结论

当前 UI 门槛的 7 个 spec 文件共 **59 条测试通过，0 失败、0 跳过**：

```bash
cd frontend
npx playwright test \
  e2e/frontend-redesign-baseline.spec.ts \
  e2e/conversation-lifecycle.spec.ts \
  e2e/results-usage-workspace.spec.ts \
  e2e/production-workspace.spec.ts \
  e2e/workspace-navigation.spec.ts \
  e2e/assets-workflow.spec.ts \
  e2e/projects.spec.ts \
  --reporter=list
```

`npm run build`（`vue-tsc --noEmit` + `vite build`）通过；最终部署 bundle 为 `index-BzLFqWIv.js` / `index-BN99DDPg.css`。

除 `projects.spec.ts` 使用真实 Java 认证、项目与隔离 API 外，本组用例使用受控 HTTP 响应或本地媒体字节；未上传成功素材、未调用真实模型、未提交真实预览/确认/导出，未重建共享服务。

## 迁移的文件

| 文件 | 原问题 | 迁移方式 | 结果 |
| --- | --- | --- | --- |
| `frontend-redesign-baseline.spec.ts` | 旧版 baseline 使用素材/制作/作品页签和右侧详情列 | 用当前三栏外壳的受控 baseline 取代旧文件；覆盖内联草稿、暂不采纳、预览选择/预览卡、追问卡、素材抽屉、项目切换器 | 1 passed |
| `conversation-lifecycle.spec.ts` | 每次进入「制作」页签；预览记录藏在详情列 | 对话默认可见；移除页签与「预览记录」入口，保留同一条 HTTP 生命周期断言 | 5 passed |
| `results-usage-workspace.spec.ts` | 作品页签、窄屏隐藏的产物面板、用量入口 | 产物面板默认可见或按需展开；用量从左侧栏打开；窄屏先展开工作区再操作，完成诊断后收起产物 | 15 passed |
| `production-workspace.spec.ts` | 左右 `tabpanel`、桌面并排布局、窄屏「对话/预览」切换 | 草稿改为消息流内联卡；重写桌面/窄屏/滚动/来源播放断言；来源播放改走媒体弹窗 | 15 passed |
| `workspace-navigation.spec.ts` | 项目列表页、三页签、视图偏好 localStorage、返回项目列表 | 项目切换器 + 浏览器 history；会话切换保留未发送文本与引用；抽屉焦点；空权限项目；窄屏自动收起 | 7 passed |
| `assets-workflow.spec.ts` | 素材常驻页签、检索与素材同列、播放器返回素材列表 | 统一从素材抽屉进入；素材/查找 tab 保持挂载以保留查询；媒体弹窗关闭后回到原 tab | 15 passed |
| `projects.spec.ts` | 项目列表页、三页签、窄屏账户按钮 | 新建项目弹窗、项目切换器、素材抽屉、产物面板；账户从展开后的左栏进入 | 1 passed |

新增 `e2e/fixtures/workspace.ts` 作为导航 helper（打开材料/查找、展开产物/工作区、选择项目/会话、新建项目、创建聊天、账户）。生产 UI 增加 `data-project-id`、`data-conversation-id`，只用于稳定的可访问定位。

## 迁移中发现并修复的 UI 问题

1. **「有新消息，回到底部」在滚动容器内**：按钮在旧代码中位于消息流底部，读者向上回看时按钮本身也滚出视口。已移到消息滚动区外，仍保留在任务状态/输入区上方。
2. **素材抽屉切换 tab 丢失查询**：`AssetLibrary`/`SpeechSearch` 原先用 `v-if` 互相卸载，切到素材再回到查找会清空查询。改为 `v-show`，两个面板保持挂载；焦点陷阱仍只命中可见元素。
3. **窄屏首屏自动收起竞态**：面板初始 `ref(false)` 后由 `onMounted` 再收起；测试/用户在此期间展开会被回调重新收起。响应式状态改为按 `window.innerWidth` 初始化，`onMounted` 只记录断点，不再二次收起。
4. **重复「制作对话」landmark**：外层 `.chat` 和内部 `ConversationPanel` 同名，产生两个 region。外层改为「对话工作区」。
5. **新聊天只能引用会话已有素材**：新建聊天没有 assetIds 时引用素材区域不显示。改为展示当前项目全部素材，引用仍以本轮 ASSET 引用发送。
6. **方案引用后焦点停在右侧**：键盘 Enter 引用成片后焦点仍留在右栏。现在引用后把焦点送回 `#conversation-input`。
7. **预览卡缺少稳定语义**：内联预览卡补 `aria-label="预览请求"`，供状态合并断言定位。

## 未迁移与边界

- 本次只迁移**当前 UI 门槛**。仓库仍有 38 个旧/专项 spec 文件，其中：
  - `montage`、`editing-brief`、`model-record-limit`、`conversations` 的旧表单分支依赖已按产品要求移除的「需求类型/方案主题/旧会话创建」控件；按 `docs/verification/frontend-redesign-step1.md` 的测试清单退役，不由迁移恢复旧控件。
  - 真实模型/付费路径（`natural-language*`、`vision`、`analysis*`、`semantic*` 等）、重建共享服务的故障注入（`auth`、`uploads`、`upload-*`、`analysis-*` 等）不在本轮默认门槛内，未运行。
  - 这些文件中的部分后端断言仍可能有效，但需要单独环境/预算和当前入口的独立适配，不能把未运行当作通过。
- `frontend-redesign-baseline` 的真实种子分支已移除；当前 baseline 不依赖 `.scratch` 缓存。真实已有项目的手工冒烟仍记录在[设计说明](../../design/frontend-v2-design.md)。
- 未运行 Java/Python 回归：本阶段没有修改后端、Agent 或任务契约；`docker compose build business-api` 的 Maven package 通过。浏览器回归使用现有部署，不重建共享服务。
- 未执行真实付费模型、真实上传后分析或真实渲染；未产生额外模型费用。
