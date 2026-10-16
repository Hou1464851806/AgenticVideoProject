# 前端重构步骤 4 验证记录

日期：2026-10-16。基准：步骤 3 提交 `790653a`。按计划只改变制作展示、输入和核查状态；沿用步骤 1 的公共 HTTP 边界、非付费隔离媒体及三视口基线。

## 实现与契约

- 制作区桌面对话／预览双栏，窄屏使用对话／预览子页签；切换主视图保留输入、引用和唯一当前草稿控制器。IME 组词不提交，Ctrl／⌘ Enter 发送已提交原文；阅读历史时使用“查看新消息”而不强行滚动。
- 引用显示素材／版本／输出名称，可移除；名称不进入引用 API。条件摘要按需展开，当前草稿展示输出标题、精确时长、画幅、字幕与片段依据。
- `DraftContent` 只负责展示。`DraftCard` 仍独占当前不可变草稿的操作和幂等键；多输出一次预览一条，确认接受全部输出，未预览也可确认，不自动导出。资格依赖快照，失败／停止不恢复过期资格。
- 预览请求与消息按产物身份合并，独立历史产物保留来源。不同产物播放器使用独立 key。UNKNOWN／预算原因与独立停止协议保留。
- 草稿片段及证据原片在制作右侧回看；每次选择明确打开原片，采用共享请求序号忽略过时播放授权和错误，关闭后恢复原详情。

核对后修正了步骤 1 的一项契约判断：`GET /conversations/{id}/drafts?before=…` 是旧版 INCOMPLETE 回复分页，并不提供历史结构化编辑草稿。本步只缓存本会话实际观察到的当前结构化草稿；刷新后历史仅有原文时展示只读原文，不伪造结构、不增加接口。这是现行数据边界，非恢复历史编辑能力。

## 范围与检查

HTTP 回归覆盖制作模块及直接受影响的导航、素材和生命周期。产品未修改 Java/Python、API 协议、草稿哈希、MQ、费用或权限模型，不扩大为服务全量；阶段完整非付费回归和后端打包按步骤 6 执行。

```sh
# 仓库根目录；Vite 已在 5173，真实服务在 localhost:8080
E2E_BASE_URL=http://127.0.0.1:5173 E2E_REDESIGN_SCREENSHOTS=../docs/verification/frontend-redesign-step4 npm --prefix frontend run test:e2e -- production-workspace.spec.ts conversation-lifecycle.spec.ts workspace-navigation.spec.ts frontend-redesign-baseline.spec.ts assets-workflow.spec.ts --grep-invert 'real existing'
npm --prefix frontend run build
RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp -pl backend '-Dtest=LocalMediaIntegrationTest#unacceptedAndSavedSilentDraftsRenderThroughMqWithTheirOwnSources' -Dlifecycle.browser=true -Dlifecycle.model=false test
E2E_BASE_URL=http://localhost:8080 E2E_UI_ORIGIN=http://127.0.0.1:5173 E2E_REDESIGN_REAL=1 E2E_REDESIGN_SCREENSHOTS=../docs/verification/frontend-redesign-step4 npm --prefix frontend run test:e2e -- frontend-redesign-baseline.spec.ts -g real
```

| 检查 | 最终结果 |
| --- | --- |
| 制作 HTTP 边界模块 | 14 条通过，含原片再次打开和授权乱序 |
| 相邻模块及三视口受控基线 | 素材 15、生命周期 5、导航 11、基线 3；与制作合计 48 条，0 失败、0 跳过 |
| 真实已有服务基线 | 3 条通过，0 失败、0 跳过，仅读取已有项目，不调用模型 |
| 隔离真实服务与媒体 | LocalMedia 指定 Java 测试及内嵌浏览器均通过；最终代码实测草稿 360p、冻结 V1、720p／1080p 解码、时间推进、下载、独立任务及缺失文件删除恢复 |
| 静态检查 | 独立 vue-tsc、生产构建通过，55 模块；git diff --check 与 99 个本地链接通过 |
| 三视口实屏 | 47 张 PNG，包含真实隔离媒体桌面／手机；无页面横向溢出，已审查双栏和窄屏输入／预览 |
| Standards | 首轮 2 项 P2 已修正，复核无剩余发现 |
| Spec | 首轮 1 项 P2 已修正，复核无剩余发现 |

两轴按 code-review 技能独立审查，以 git diff --cached 790653a 为固定比较；产品修正后完成上述最终回归，此后仅更新记录和进度。

## 开发中发现与修正

- 浏览器旧测试在“预览记录”查找隐藏的当前草稿按钮而失败，改为先选择“当前草稿”，保留原有确认／播放／独立任务断言。
- 47 条相邻回归首条在 Chrome newContext 启动超时，尚未执行应用断言；其余 46 条通过。最终串行重跑完整清单。
- Standards 首轮发现再次核查隐藏和播放授权乱序两项 P2；Spec 首轮发现再次核查隐藏一项 P2。修复后增加不关闭旧播放器再打开及授权乱序回归。
- 一次从仓库根误执行 npm exec vue-tsc，调用了非项目本地工具并报工具包导出错误；正确 frontend 工作目录的类型检查与构建通过，未改变依赖文件。

[步骤实屏目录](frontend-redesign-step4/)含受控三视口与已有服务证据。原步骤 1～3 图不覆盖。静音媒体自动播放验证不代表真实讲话音画质量；付费模型、人工音画及部署未运行。
