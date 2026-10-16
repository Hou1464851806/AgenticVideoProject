# 前端重构步骤 3：素材、内容查找与原片回看

日期：2026-10-16（Asia/Shanghai）。基准：步骤 2 的 `459ba3a`。依据[设计](../frontend-redesign-design.md)、[实施计划](../frontend-redesign-implementation-plan.md#步骤-3-重构素材与内容查询)和[步骤 1 有效测试清单](frontend-redesign-step1.md)。本步为完整阶段内部步骤，服务全量仍留步骤 6。

## 实现与验收范围

| 标准 | 实现与对应检查 |
| --- | --- |
| R3-01 | 上传类型只补后端已有 assetId。AssetLibrary 组合 UploadPanel，按上传与素材身份合并；完成且可播放的条目显示素材操作，验证中／失败的关联条目保留上传反馈，同名不同 assetId 仍分别存在。没有伪造时长、封面或缩略图。 |
| R3-02 | 原传输、哈希、排队、暂停／继续、重选及取消逻辑保留。条目显示确认字节进度、刷新需重选的原因、恢复期限、过期与独立清理状态；不会把取消等同空间已释放。 |
| R3-03 | 能力摘要分别说明可播放、语音与画面，完整阶段按需展开；成功阶段没有重试按钮，无音轨为限制提示。失败原因和既有允许重试保留，默认不查询或展示索引配置。 |
| R3-04 | 查询框前置，默认语音／原文及请求参数不变，筛选有当前摘要。结果绑定提交时的查询及来源，修改输入不改变旧结果复查对象；上下文、原片定位、无命中、部分覆盖和稀疏帧限制保留。索引重建从普通查询覆盖移到高级维护。 |
| R3-05 | 候选与覆盖补查仍由明确点击发起；首轮预算、已用额度、原因、扩大范围和费用方案保留。接受与拒绝分别提交原决策，子轮次独立；展开详情、切换素材不自动执行分析或重建。 |
| R3-06 | 素材中的原片回看留在素材视图，返回后恢复查询和结果。视频在详情前；编码、帧率、能力和精确定位按需展开，普通播放无内部 ID 或索引参数。方案片段边界与同素材续签保留；修正首次访问失效时续签把尚未应用的请求定位点覆盖成 0 秒的问题。 |

诊断与高级维护为素材视图内的次级入口，选中素材才挂载维护详情。普通播放与普通能力详情不显示模型、向量维度或 BM25 控件。制作内的原片核查双栏按计划在步骤 4 处理；没有把作品方案或制作协议提前迁移为新模型。

## 步骤 6 费用复核更正

本记录的历史命令与当次测试结果保留。步骤 6 核对源码和当前部署后发现：开启自动分析时，静音成功上传也会入队视觉模型；原文将全部 12 条称为无模型费用不准确，不能据此推断该次没有模型费用。当前阶段严格非付费清单只保留不可解码续传及验证拒绝用例，成功媒体使用隔离 LocalMedia；详见[阶段记录](frontend-redesign-stage.md)。不以历史 12 条的通过替代本轮非付费结果。

## 验证环境与选择依据

新增 `assets-workflow.spec.ts` 使用现行公共 UI 与 HTTP 边界，覆盖身份合并、状态／恢复、失败阶段、查询语义与来源、预算授权、播放签名、初始定位、片段停止、上下文及三视口。相邻回归为完整 `workspace-navigation.spec.ts`、`conversation-lifecycle.spec.ts` 和受控基线。

真实服务只选步骤 1 清单确认无模型费用且不重建服务的用例：`uploads.spec.ts` 两条；`upload-resume.spec.ts` 除 actually expired part URL 外五条；`media.spec.ts` 的静音播放、不可解码、哈希不符与超时长四条；`analysis.spec.ts` 的静音独立阶段一条。讲话分析、audio-offset、所有 renderSeed 冷建、真实视觉补查与 Compose 故障注入不运行。

沿用步骤 2 的 current-frontend 夹具：浏览器保持 `http://localhost:8080`，只从 `http://127.0.0.1:5173` 取当前 UI，API 请求直达真实 Java。Chrome 的本地网络访问权限只在该测试上下文、该 Origin 显式授予，使浏览器能直传存储；不关闭跨域／签名校验，不修改共享服务。真实用例上传静音或不可解码文件，实际媒体由 Java／MQ／存储处理。

截图默认写 test-results，只有显式 E2E_REDESIGN_SCREENSHOTS 才写步骤证据目录，避免回归时覆盖旧步骤图。

```sh
# frontend/ 内；Vite 与已有真实服务需已启动
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e -- assets-workflow.spec.ts -g 'completed uploads'
E2E_BASE_URL=http://127.0.0.1:5173 E2E_REDESIGN_SCREENSHOTS=../docs/verification/frontend-redesign-step3 npm run test:e2e -- assets-workflow.spec.ts workspace-navigation.spec.ts conversation-lifecycle.spec.ts frontend-redesign-baseline.spec.ts --grep-invert 'real existing'
E2E_BASE_URL=http://localhost:8080 E2E_UI_ORIGIN=http://127.0.0.1:5173 npm run test:e2e -- uploads.spec.ts media.spec.ts analysis.spec.ts upload-resume.spec.ts -g 'directly uploads|lost create|local MQ|undecodable|independently rejects|over-duration|verified silent|pause settles|pausing while|paused queued|persistent failure|refresh reselects'
E2E_BASE_URL=http://localhost:8080 E2E_UI_ORIGIN=http://127.0.0.1:5173 E2E_REDESIGN_REAL=1 E2E_REDESIGN_SCREENSHOTS=../docs/verification/frontend-redesign-step3 npm run test:e2e -- frontend-redesign-baseline.spec.ts -g real
npm exec -- vue-tsc --noEmit
npm run build
```

## 实际结果与必要审查

| 检查 | 最终结果 |
| --- | --- |
| 素材／查询／播放 HTTP 边界 | 15 条通过，包括三视口、初次签名失效后的定位、方案原片结束边界 |
| 导航、当前生命周期、受控基线 | 11 + 5 + 3 条通过；与素材文件同次共 34 条，0 失败、0 跳过 |
| 真实无模型上传、续传与媒体 | 12 条通过，0 失败、0 跳过；包括暂停／队列／成功分片保留、刷新重选及同名不同内容、丢失响应幂等、Range 解码播放和签名续期、无音轨、无效文件及清理 |
| 真实已有服务三视口基线 | 3 条通过，0 失败、0 跳过；仅读取已有项目／素材／方案，不调用模型 |
| 类型与生产构建 | 独立 vue-tsc --noEmit 与最终 npm run build 均通过，构建 52 个模块 |
| 实屏与静态检查 | 36 张三视口截图；无页面横向溢出，局部细节与主次操作已审查；本地文档链接、截图尺寸和 git diff --check 通过 |
| Standards 审查 | 0 项发现：最小范围、状态身份、错误反馈与职责符合根规范 |
| Spec 审查 | 0 项发现：R3-01～R3-06 无缺失、错误实现或范围扩张 |

审查比较 git diff --cached 459ba3a；最终验证均在审查覆盖的产品代码上完成，此后仅更新步骤进度和验证文档。阶段完整非付费回归尚未执行，不沿用旧阶段结果。

开发中失败与修正：

- 先红后绿覆盖了身份合并、普通详情隔离维护控件、筛选与结果绑定；维护表单补显式 label 关联，保证标签精确可访问。
- 原片夹具开始只返回完整 200，无法代表真实 S3 的 Range 行为；补齐共享媒体边界夹具的 206／Content-Range 后，真实解码、定位和片段停止可验证，未为测试修改播放停止协议。
- 新增“初次签名失效后仍定位到原请求时间”用例先失败，修正 loadedmetadata 对未应用定位与续签恢复位置的优先级，再补跑。
- 真实直传最初失败，确认是 Chrome 拒绝 loopback 地址空间；测试上下文授予 local-network-access 后单条真实上传／无效媒体清理通过。诊断拦截与日志不保留在测试或版本库中。

Java/Python 完整回归、后端打包、付费模型、人工音画和部署未运行。上传核心只有类型新增，未改传输行为，Java 业务／持久化／跨语言契约不受影响，不为该字段运行后端全量；阶段完整回归仍按步骤 6 执行。

## 证据

本步截图目录：[frontend-redesign-step3](frontend-redesign-step3/)。包含三视口素材能力、查询上下文、原片播放器、既有草稿异常与用量冒烟，并复用现有账号和已有素材采集真实登录／列表／三区域。原步骤 1／2 图保持不变。静音媒体与受控文字不证明真实讲话音画质量。
