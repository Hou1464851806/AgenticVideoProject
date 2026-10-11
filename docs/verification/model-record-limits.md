# 模型调用记录大小限制修复

基线：`9415d78180b115aaab59374c0b0aba9609cc405a`。

## 用户确认的修改范围

方案一：保留 PostgreSQL 完整 request／response、状态及用量；将 100000 字符限制改为可配置 UTF-8 序列化字节限制，默认 request 16 MiB、response 4 MiB。保留输入一致性、成功回执复用、不确定回执停止自动重复付费及租约校验。确定性拒绝直接反馈具体原因，不无效重试；页面刷新后仍可查看原因。Token 预算、历史选择、候选长度及证据读取不在本次范围；P1／P2 保持暂停。

## 配置与语义

- `MODEL_RECORD_REQUEST_MAX_BYTES`：默认 `16777216`。
- `MODEL_RECORD_RESPONSE_MAX_BYTES`：默认 `4194304`。
- 校验内部登记的 `data.request`／`data.response`，按 Jackson 紧凑 JSON 的 UTF-8 编码计数，超过才拒绝，等于上限允许。外围凭证及 HTTP 头不计入。
- 校验发生在 JSON 已解析后，是记录字段大小保护，不是整个 HTTP 请求的传输限制，也不是模型 Token 预算。
- 超限返回 HTTP 413、`MODEL_RECORD_TOO_LARGE`、`retryable=false`，详情仅含记录种类、实际字节与上限。公开任务反馈只展示受控生成的原因，不公开原始请求或凭据。
- 默认值不保证填满供应商上下文窗口；完整记录仍存 PostgreSQL JSONB，后续可依据实际用量调整配置。

## 验证

- RED：120000 字符的调用请求被旧登记入口返回 400；GREEN：新默认限制接纳，响应同样超过旧限制仍可保存及完整重放；同编号换输入仍返回 409，不确定提交不能重复获得付费资格。
- UTF-8 边界：独立配置 request 512、response 256 字节；200 个“中”的 JSON 是 614 字节，正确拒绝；请求精确 512 字节允许。响应 314 字节拒绝、精确 256 字节允许，拒绝后仍能登记合法的同次响应。
- 浏览器 RED：超限只有通用失败，公开历史没有具体原因；GREEN：真实服务配置低上限，经外部 HTTP 网关观察准备请求只一次，模型调用零次，页面及刷新均显示 512 字节上限。
- 响应拒绝保护：拒绝保存后立即重放 PREPARE，canSubmit=false／UNCERTAIN；浏览器 RESPONSE 网关验收仅一次真实 HTTP 模型响应、PREPARE 与 SUCCEEDED 各一次，失败页面及刷新均显示 128 字节上限。
- Java 全量 43 项通过，零失败／错误／跳过，Maven verify 成功（1 分 22 秒）。浏览器全量 42 项通过（9.8 分钟）；随后补跑 REQUEST／RESPONSE 两项边界均通过（1.1 分钟），其中 REQUEST 为重复验收，合计覆盖当前 43 个浏览器用例。
- 前端类型检查／生产构建和 business-api、media-worker、agent-service 全部镜像构建成功。
- 原失败的真实上下文在隔离任务中核查：完整请求 114929 字符，DeepSeek 实际输入 42005 Token，返回 propose_edit_plan 候选；请求登记、完整响应保存和成功回执重放均通过。该核查不发布正式方案，不覆盖原用户任务或方案；生成到正式版本的既有真实模型浏览器用例也通过。
- 独立 Standards 检查生产修改及初始测试 PASS；独立 Spec 检查并复查新增响应边界测试 PASS。Standards 对补充测试的追加独立复查因额度不足未完成，主任务已复核该测试；未新增生产修改。
