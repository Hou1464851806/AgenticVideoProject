# #34 新聊天与自然语言查找素材

实施基线 `58353cf`。沿用已确认的公开 HTTP、真实 PostgreSQL、真实 Agent 与浏览器边界。

## TDD 与验证

- 新聊天接口先因必填标题返回 400；实现无表单创建、固定素材范围、持久欢迎消息和创建幂等后通过。
- 浏览器先找不到“新聊天”；接入按钮后，点击即创建会话，显示欢迎语与输入框，刷新不重复欢迎。
- 收窄范围工具先返回 400；实现后只允许逐步收窄当前范围。工具重放一致，原任务输入不变，新上传素材不会自动进入旧范围。
- 真实 Agent 根据自然语言查找讲话证据、返回张明及可定位引用。新增检查发现页面范围数量未同步；修复后显示一份素材，刷新恢复原回答。

接口测试 `ConversationApiIntegrationTest` 21/21 通过；浏览器 `natural-language.spec.ts` 2/2 通过；前端 `vue-tsc --noEmit` 与 Vite 构建通过。保留原有具名会话入口兼容性。完整回归与双轴审查在 #34–#38 全部实现后统一运行。

日志：`/tmp/agenticvideo-issue34-http.log`、`/tmp/agenticvideo-issue34-browser-final.log`、`/tmp/agenticvideo-issue34-types-final.log`。红灯记录在同前缀的 `red` 与 `scope-browser-red` 日志中。
