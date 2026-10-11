# #12 查询画面与可见文字并核查观察时刻

固定审查基线 `499b80998caa853915f35d399b0a02c621971f4c`。沿用公开 HTTP、真实 PostgreSQL / Silo / Qdrant / 百炼及 Compose 浏览器边界。

## 实现及实际验证

- Java Worker 在媒体准备阶段抽取最多 25 张代表帧。按实际解码 PTS 减容器起点映射到原视频毫秒；记录原始 PTS、图像 SHA、抽样规则和稀疏覆盖。最长采样间隔随片长增加；没有逐帧完整识别承诺。用户在素材库点击“分析画面”提交独立 VISION 任务，不自动为每个上传支付视觉调用。
- Python 只提交单张图片及图像观察提示，不输入语音内容；持久化付费调用提交资格、模型名称、请求编号、实际用量、原始观察。相同像素在多个时刻保留独立帧观察，但复用同一成功模型调用。提交结果未知不自动重复付费。
- 当前北京凭据实际可调用模型为 `qwen3-vl-plus`。最初选择的 `qwen3-vl-flash` 及 `qwen3-vl-flash-2026-01-22` 在真实原生端点返回 404 / InvalidParameter / Model not exist，不能将文档列出的可用性当作本账号实际成功。使用 plus 后真实模型请求成功；本票保存调用名称与参数，未声称提供方 alias 是不可变快照。
- SPEECH、VISUAL、VISIBLE_TEXT 各自保存及召回；视觉返回 observedAtMs / FRAME_OBSERVATION，公开列表、检索、上下文及 Agent 引用均不把单帧描述成区间。观察帧通过已鉴权业务接口签发短时链接；原视频定位使用观察时刻。
- 视觉证据复用中文 Dense / BM25 / 字面召回与 RRF，来源、项目及有效代次在召回前过滤。VISION / INDEX 状态独立；视觉失败和取消不更改成功语音证据。索引发布若发现分析新增单元，继续建立完整代次并复用成功向量，避免语音与视觉完成竞争导致漏索引。

## 人工标注样本

`labelled-vision-vfr` 为无音轨的红／蓝矩形及中文文字，独立 ffprobe 测得帧时刻 0、2320、4760、7080 ms；人工标签红图“证据42”、蓝图“画面17”。真实 VLM 返回文字，字面可见文字检索命中红图三个时刻，2 个唯一图像对应 2 次真实模型调用，每个结果可 GET 佐证帧。原始标签见 frontend/e2e/fixtures/labelled-vision-vfr.json。

`speech-mention-only` 为 4351 ms 的红矩形／证据42，中文脚本提到“猫”，画面没有猫。真实 ASR 的语音证据含“猫”；VLM 画面观察不含猫，视觉字面查询猫为空，语音查询有结果，可见文字查询仍可命中。多次重复帧只调用一次视觉模型。完整发布索引包含所有语音和视觉单元。

这些是人工可核对的合成接口样本，不能推断自然长视频、短暂小字或复杂动作的生产准确率。稀疏采样及模型识别都可能遗漏；未命中不证明不存在。

## TDD 与审查

1. 视觉入口 404 → 实际 VLM / VFR / 重复帧 / Qdrant 检索与签名帧访问通过。
2. 缺少证据类型界面 → 选择可见文字、观察帧、邻近上下文、原视频跳转通过。
3. 并发阶段索引仅 1 单元、实际 7 单元 → 发布后补建完整代次，真实回归通过。
4. Agent 来源参数被拒绝 400 → 受控视觉查询通过；上下文和正式引用原先没有观察点 → 观察点引用通过。
5. 取消后阶段仍 RUNNING → CANCELLED，晚到完成 409，语音继续可查。
6. 无分析 head 读取证据 500 → 空观察 200；视觉检索单元明确来源且没有 start/end。

Standards PASS（0）；Spec 初次 2 项，修复后 PASS（0）。后端完整 `mvn -B -ntp verify` 已通过，66 项测试、0 失败／错误，BUILD SUCCESS（5:06）。Frontend vue-tsc / Vite 构建及三服务镜像构建通过。日志 `/tmp/agenticvideo-issue12-full-verify.log`、`agent-ui-build.log`、`full-images.log`、`reviewed-images.log`。真实观察及用量见 [原始记录](issue-12-real-visual-observations.json)。浏览器完整 **56/56 PASS（13.4 分钟）**，包括真实 DeepSeek 通过受控 VISIBLE_TEXT 工具检索并发布观察点引用。日志 `/tmp/agenticvideo-issue12-full-e2e.log`。运行服务保持可体验。
