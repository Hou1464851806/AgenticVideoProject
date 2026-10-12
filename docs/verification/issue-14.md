# #14 只恢复失败分析阶段并重建检索索引

固定审查基线 `c09c8c5`。沿用公开 HTTP 和真实 PostgreSQL／Silo／RabbitMQ／媒体／模型／Qdrant 及 Compose 浏览器边界。

## 实现与 TDD

- 五个独立状态、失败阶段恢复、独立素材状态入口。临时媒体故障恢复，确定无效和进入清理的文件拒绝；检查所有者、容量、停止状态和有效任务复用。
- Embedding 状态缺失 → 独立 FAILED，INDEX 保持 NOT_STARTED，恢复保留语音证据。未知回执不重复付费。
- 原先配置被忽略（512 实际返回 1024）→ 持久 model／dimension／BM25／collection；不兼容配置独立索引。
- 新 INDEXING 动作 400 → 全部向量成功后独立索引 RUNNING，取消／过期／迟到不发布。
- ASR 恢复 409 → 查询同一提供方任务；明确 FAILED 终态的显式重试追加回执。UNKNOWN 但 code 包含 FAILED 原先误放行 → 按 JSON status 精确拒绝。
- MEDIA 状态缺失 → 安全重试临时故障；确定无效文件 409。原文件在临时故障期间保留。
- 两 collection 合并将第二组第 1 名错误记为第 2 名 → 按各集合独立名次融合，稳定处理同分。HTTP 用真实 Qdrant 与持久向量 fixture，查询 Embedding 位于外部 API fixture；真实模型另由浏览器验证。
- 全向量复用时界面不刷新索引完成 → 共享状态面板持续刷新，包含不依赖播放的入口。

HTTP 索引／阶段集成文件 8/8 通过。真实浏览器已经验证 Embedding 凭据暂缺、512 维实际模型和发布、视觉凭据暂缺恢复、Qdrant 点写 503；成功批次可在执行器没有模型凭据时完成索引重建，证明不再次调用模型。完整 Maven verify 已通过（78 项，0 失败／错误／跳过），前端类型检查与构建及三个 Compose 镜像构建已通过。最终阶段浏览器 3/3 通过（1.9 分钟）；完整浏览器 62/62 通过（16.2 分钟），日志 `/tmp/agenticvideo-issue14-final-full-browser.log`。

模型与维度约束核对自 [百炼通用文本向量 API](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)。

## Standards

复审 PASS（0）。首次 RRF 跨 collection 排名违规已修复。

## Spec

复审 PASS（0）。首次缺媒体独立恢复入口及多配置排名问题均已修复。

最终阶段验证日志：`/tmp/agenticvideo-issue14-final-stage-rerun.log`。全量后端日志：`/tmp/agenticvideo-issue14-reviewed-full-verify.log`。故障注入前等待原索引完成，避免测试主动中断无关的付费 Embedding；未知提交回执仍保持禁止自动重付。
