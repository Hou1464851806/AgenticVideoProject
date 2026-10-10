# 任务通知与公开事件协议

状态：Q58 已确认基线下的协议规格 v1，尚未联调。RabbitMQ 执行通知、内部 Agent 活动回报和浏览器 SSE 是三类消息，分别处理；不把供应商流或 LangGraph 原始事件直接转发前端。

## RabbitMQ 执行通知

```json
{
  "schemaVersion": "1.0",
  "eventId": "<outbox-uuid>",
  "taskId": "<task-uuid>",
  "taskType": "AGENT_TURN",
  "projectId": "<project-uuid>",
  "occurredAt": "<UTC timestamp>"
}
```

消息不带视频二进制、模型密钥、长期签名 URL 或整个对话历史。任务输入以业务后端持久记录为准，收到通知后执行服务经 claim 获取固定输入和短期执行权；通知中 projectId 只做关联，不能赋予权限。

首期按执行能力划分 Java 媒体、Python 分析／对话和索引同步队列，使用持久队列、持久消息与 publisher confirm；命名与并发按 Compose 配置固定。路由键携带协议版本／任务类别，不让模型自己发布消息。

消费者策略固定为：成功取得并提交持久执行权后 ACK，随后由执行器运行并续租；收到已完成／已有有效执行者的重复通知不启动新尝试。claim 结果不明则查询／幂等重试，不未经确认就启动；领取失败的通知有限重投／死信，避免瞬时反复 requeue。领取后进程崩溃由 PostgreSQL 租约恢复，不依赖未 ACK 消息。Outbox confirm 不等于任务成功。

claimRequestId 绑定执行器启动实例，本地按 attemptId 注册唯一执行入口；同一次领取的重试响应不允许再次启动同尝试的进程。重启后未知的旧注册由执行器核对与租约恢复协调，不能仅因收到一份已有凭证就假定可以重复运行。

恢复扫描处理过期执行占用、到期重试、未发布 Outbox 和孤儿产物，核对执行器与已有外部任务，再重新派发。旧尝试仍可能运行，所以执行目录、取消控制和结果 fencing 同时必须实现。数据库 lease／heartbeat 参数待实测，不假定 Broker 处理 exactly-once。

## 内部 Agent 回报

执行服务回报带 taskId、attemptId、leaseToken、producerSeq、type 和 payload；服务凭证先认证，业务后端核对项目、任务和当前尝试。原始工具调用事件、私有推理／路径和外部凭证不接受为公开 payload。

- producerSeq 是该尝试的确定活动编号，同编号同内容幂等，同编号不同内容冲突。
- 文本增量按 messageId／attemptId／baseContentVersion／offset 应用，offset 明确按 Unicode 码点计数，不能混用 UTF-8 字节和 JavaScript UTF-16 长度；后端核对累积内容长度和版本。未完整或乱序增量不拼接，调用方按顺序重传／读取现有草稿校准。
- 关键活动转换为消息／问题／业务结果和公开事件，同事务写入。进度字段使用阶段和实际已处理数量，不让模型虚构总体百分比。
- await-input、complete、fail 使用各自结果接口，不能仅发一个活动事件就宣告成功或停止续租。
- 用户可见文字合批后存草稿和事件；最终消息 completion 有完整结果或可读取引用，未完成草稿保留未完成标记。

## SSE 事件封装

一个项目使用一条公开事件流，前端按 conversationId／taskId 分发给对应界面；每条事件仍需该项目权限。首期不用每个聊天／上传／渲染组件单独长期连接，避免连接与恢复管理重复。

```text
id: <opaque-project-cursor>
event: assistant.delta
data: {"schemaVersion":"1.0","eventId":"<uuid>","projectId":"<uuid>","eventSeq":"42","conversationId":"<uuid>","turnId":"<uuid>","taskId":"<uuid>","attemptId":"<uuid>","occurredAt":"<UTC timestamp>","payload":{"messageId":"<uuid>","baseContentVersion":3,"contentVersion":4,"offset":72,"appendText":"已找到一个更完整的例子。"}}

```

cursor 绑定项目和提交序号但不是授权；eventSeq 用十进制字符串避免 JS 整数精度问题。消息数据库身份和事件游标不能互换。事件至少一次展示，客户端按项目序号／eventId 去重。

## 公开事件目录

| 类型 | 关键 payload | 前端效果 |
| --- | --- | --- |
| upload.updated | uploadId, status, confirmedBytes, expiresAt, cleanupStatus | 更新上传会话与恢复期限，不凭事件强行将进度设为 100% |
| asset.updated | assetId, validationStatus, stageSummary, hidden | 更新可选与分析状态 |
| analysis.stage.updated | assetId, analysisId, stage, status, coverage | 语音／视觉分阶段就绪，显示缺口 |
| turn.accepted | turnId, inputMessageId, taskId, fixedContext | 标记发送已被持久接纳，避免重复气泡 |
| run.started | taskId, attemptId, phase | 标记当前尝试；重试后的草稿不接旧文本 |
| activity.updated | phase, completedItems, totalItems（已知时） | 显示排队／检索／复查／编排／验证等公开活动 |
| assistant.delta | messageId, attemptId, baseContentVersion, contentVersion, offset, appendText | 按版本应用文本，缺口读取草稿快照 |
| assistant.completed | messageId, contentVersion, content 或 contentRef | 完成公开回复，必要时读取权威正文 |
| question.created | questionId, turnId, body, options, context | 显示待回答卡片，与原轮次关联 |
| question.closed | questionId, reason | 关闭已回答／取消／新需求替代的问题 |
| plan_revision.created | planId, revisionId, revisionNumber, parentRevisionId, outputSummary | 提示新版本，保持用户正在查看的版本 |
| artifact.available | artifactId, kind, revisionId, outputId, mediaInfo | 显示预览／成片就绪，按需获取访问链接 |
| artifact.removed | artifactId, reason, rebuildable | 显示预览可重建或产物已删除 |
| run.finished | taskId, resultRefs, turnStatus | 使用持久结果判断成功 |
| run.failed | taskId, publicError, canRetry | 标记失败草稿和重试入口，不复制用户消息 |
| run.cancelled | taskId, cleanupStatus, externalStatusKnown | 明确停止业务发布，本地清理／外部服务状态另行说明 |

事件不将 signed URL 当永久内容；对象 URL 在点击播放／下载时获取。表中字段按 type 对应 schema 校验，非法类型／超限 payload 拒绝。事件与本文 API 规格的结构化消息卡对应，不靠自然语言字符串匹配触发版本或渲染。

## 快照、订阅与重连

1. 登录后读取项目／会话一致快照：在短 REPEATABLE READ 事务内读取业务内容与 project_event_counters，返回状态和 cursor。独立历史分页不宣称是恢复快照。
2. GET `/api/v1/projects/{projectId}/events?after={cursor}`。首次使用快照 cursor；已有连接重连时使用已收到的 Last-Event-ID，页面刷新自行恢复游标／快照。
3. 服务端验证项目权限、cursor 项目与保留范围，按已提交 eventSeq 顺序查询重放，再持续查询／推送。日志是权威，内存通知或 Redis 只能唤醒，漏掉唤醒不能漏掉日志事件。
4. 事件号在项目计数器行锁下递增，与业务变更同事务写入；同项目后续分配必须等待前次事务结束。不能用全局 sequence 最大值或产生时间代替提交顺序。
5. 客户端只将已解析且成功处理的 cursor 作为应用恢复进度，重复忽略；文本 contentVersion 不连续时查询消息快照。订阅游标前的状态已由快照覆盖，游标之后的事件不能在连接切换空隙被丢弃。
6. 保留期结束的 cursor：普通 HTTP 预检返回 410；流已建立时发送无 id 的 stream.reset 控制事件，客户端关闭连接并重新载入快照。控制事件不是新的业务高水位。

公开事件日志和 token 草稿保留期、合批频率属于可配置运维参数，完整公开消息与业务结果不跟随日志清理。历史消息加载和快照 payload 有上限，更多结果分页读取。

## 连接与认证

原生 EventSource 使用同源 Cookie，GET API 仍鉴权；写端点单独使用 CSRF。心跳采用 SSE 注释，不分配业务 eventSeq。代理禁用事件流缓冲并设置合理超时；远端断开移除订阅者，不取消执行。登录到期／权限变化关闭订阅，客户端恢复认证后重新获取可访问状态。

有活动任务时保持项目订阅，切换项目关闭旧订阅；无活动时可按界面需要断开。正常 EOF 不是成功信号，连接会自动重连，因此终态由公开事件／状态查询决定。fetch SSE 作为未来 Bearer 场景替代需自行正确解析，网络 chunk 不等于完整事件。

## 验收

用两个并发事务交错提交验证项目游标顺序；模拟快照后断线、重放重复、草稿增量乱序、旧尝试迟到、通知丢失但日志存在、日志过期和代理超时。验证不同项目不能订阅／恢复他人 cursor，取消／等待不由连接关闭触发，回复和版本结果在刷新后都能恢复。

机制依据沿用 [SSE 标准](https://html.spec.whatwg.org/multipage/server-sent-events.html)、[Spring async requests](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)、[PostgreSQL 序列](https://www.postgresql.org/docs/18/functions-sequence.html)、[RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms)。上述快照和日志语义是本系统设计，并非这些组件自动提供。
