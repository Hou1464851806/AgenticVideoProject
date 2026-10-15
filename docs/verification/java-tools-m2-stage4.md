# M2 阶段 4：Python 真实制作与修正闭环

日期：2026-10-15（Asia/Shanghai）。实施基准 `c5b5cbf`，起始分支 `main`、工作区干净。依据 [M2 最终设计](../java-tools-m2-design.md) 和 [阶段 3 交接](java-tools-m2-stage3.md)，本轮实施阶段 4，阶段 5 的真实付费 Agent 验收单列为后续工作。

## 实现与验收边界

- 真实模式支持 DRAFT，由图强制调用 Java `validate_edit_draft`；模型仅见四读取工具，没有校验跳过或保存工具。
- 进入制作即绑定制作需求与固定 scope；同需求修改、补答和进程恢复沿用原范围与已读证据，明确新需求取得新范围。纯问答普通后续消息仍按 M1 取得新快照。
- `draft/requirements/validation` 直接进入模型上下文，issues 不再编码截成 1000 字符；继续使用模型输入和工具结果字节预算，超限明确停止。
- 历史失败校验摘要独立持久化，使用草稿／条件哈希和可定位问题集核对进展。相同草稿、不相关问题替换或截断反馈不算实质改善；补读新证据可构成进展，修正上限仍为 3 次。
- 无法满足需求、无进展或预算耗尽返回 INCOMPLETE；CLI/status 暴露最新完整草稿和校验、实际时长、条件及默认来源、字幕缺口警告。
- `save THREAD_ID` 是独立用户命令，不调用模型。保存前持久化绑定及条件来源，先查询 Java 权威结果；响应丢失后重跑 save 恢复同一版本。结果未核对时 status 返回 INCOMPLETE，resume 直接核对保存；结果未核对前拒绝继续编辑；已保存制作只读，明确新制作创建新方案。
- 保存与执行使用同一需求文件锁，发送消息还在取得锁后核对 checkpoint，防止并行本机操作覆盖新状态。Java 的事务与回执约束沿用阶段 2～3，没有修改业务 Java、数据库或跨语言契约。

## 分层验证

测试边界沿用设计已经确定的 Agent 公共入口／CLI 与 Java HTTP，不测节点内部细节。先对当前行为运行单条失败测试，再最小实现；范围按 Agent 调用图、真实传输、制作生命周期和共享契约扩到直接关联模块。Java 使用独立 PostgreSQL 容器，不迁移用户运行数据库；没有重新分析素材或调用付费模型。

| 检查 | 阶段性结果 |
| --- | --- |
| 首条真实 DRAFT／完整反馈测试 | 先因真实模式拦截返回 ANSWER 而失败，开放并强制校验后通过 |
| 快照编辑、保存恢复及未完成信息测试 | 先因旧观察清空、无 save 入口及未暴露 validation 失败，实现后通过 |
| Python 直接相关模块 | 81/81 通过，0 失败／跳过；覆盖新增真实制作、M1 问答、Mock 草稿、补答／恢复、预算、模型协议和跨语言契约 |
| Java 直接关联校验／契约／制作 schema | `mvn -B -ntp -pl backend -Dtest=DraftValidationIntegrationTest,EditDraftContractsTest,ProductionSchemaIntegrationTest test`：32/32 通过，0 失败／错误／跳过 |
| Python 编译 | `python -m compileall -q agent-service/agent_service agent-service/tests` 通过；仓库未配置 Python 静态类型检查器，未将字节码编译报告为类型检查 |

受控模型驱动真实 Java 的自动化入口为 `DraftValidationIntegrationTest#pythonAgentRepairsRealValidationThenExplicitCliSaves`，使用真实 PostgreSQL、HTTP、Python LangGraph 和独立 CLI 子进程。两素材两输出依次经历未完整读取／切词／硬时长问题、补读、修正到 DRAFT_READY；第二轮仅修改 out-2 时长并交换顺序，校验回执返回后注入进程故障，恢复复用原调用身份与同一回执；显式 CLI 保存并重复保存取得同一版本，保存后修改保持只读。

该用例的过程证据由测试写入 `backend/target/m2-stage4-controlled.json`，包括制作需求／scope、条件来源、修正前草稿、结构化问题／哈希、通过回执及方案／版本／输出身份；该文件随测试生成、不纳入源码。初次联调因测试预期使用了错误的问题代码 RANGE_NOT_READ 而失败，按 Java 真实契约修正为 CONTEXT_NOT_READ 后通过；没有更改 Java 规则迎合测试。

本次阶段全量回归生成的受控证据身份：制作需求 `3137a6ae-c05d-4d13-816f-77af3fb1c39a`，scope `7286b38a-3c4b-4446-8cb1-4b8184a67ad6`；首次通过校验 `30dd449c-d8f0-4eed-999c-fa3121afd8fd`（6000／4000ms），编辑及故障恢复后的校验 `baa3a1ba-b698-42ef-8814-6362ddc8c915`（重排后 2000／6000ms）；保存方案 `801fc6ac-7581-4abc-93e4-ab478797a91e`、版本 `ec444103-1fdc-43e4-9a6f-6c9439b78ff8`（V1）。这些是隔离数据库夹具身份，容器销毁后不用于本机业务查询；完整草稿、条件原文、哈希、结构化问题、输出身份和恢复结果保留于上述自动生成 JSON。

保存响应丢失及原 scope 随后失效后的恢复，使用受控 HTTP 传输故障覆盖；Java 保存事务、来源和消费者行为由阶段 3 的既有用例及本轮回归覆盖。不将传输夹具等同于真实网络断线验收。

## 审查与最终验证

以 `git diff c5b5cbf...75bcde1` 进行 code-review 两轴独立审查。

### Standards

发现 1 项 P2：制作绑定的外部 JSON／契约错误进入模型格式修正循环，违反基础设施错误快速失败的约定。

### Spec

发现 2 项：P1 为补答明确 NEW 后沿用旧 execution_id，与重置的模型序号冲突，且丢失补答来源；P2 为上述绑定错误路由。两轴保留各自结果，不合并掩盖重复发现。

已修正：绑定移出模型解析异常捕获；NEW 继承原请求及关联补答，以新执行身份和新 scope 启动，复用已经解析的理解；初次制作等待中的明确 NEW 也不再误留旧范围。额外核对保存未知结果恢复：status 明确 INCOMPLETE，resume 查询权威保存结果，不再显示旧 DRAFT_READY。分别补充公共入口失败测试后修正通过。针对 `git diff c5b5cbf...65de7f2` 的独立复审确认：Standards 原 1 项已修复、未解决 0 项；Spec 原 2 项已修复、未解决 0 项，无新增问题。复审后直接相关 Python 回归 84/84 通过，0 失败／跳过。

阶段收尾验证的业务及测试代码固定为 `65de7f2`，独立复审通过后执行一次完整非付费回归及后端打包：

| 最终检查 | 实际结果 |
| --- | --- |
| Java `RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp verify` | 15:40:51 完成，用时 8 分 55 秒；185 项：181 通过、4 项真实付费分析门控跳过，0 失败／错误；Spring Boot 打包成功 |
| Python `agent-service/.venv/bin/python -m pytest agent-service/tests -q` | 88/88 通过，0 失败／跳过，10.51 秒 |
| 编译与类型检查 | Java 主代码及测试由 javac 编译通过；Python compileall 通过，未额外运行未配置的静态类型检查器 |
| 文档与差异 | 4 份变更 Markdown 的 30 个本地相对链接、`git diff --check` 通过 |

阶段 4 退出标准通过。完整 Java 回归包含新增真实 Python／Java 制作闭环、M1 四工具／快照、方案与保存、全部 53 个数据库迁移、认证、上传、分析、MQ／Outbox、真实媒体、预览／导出及对话恢复。受控故障日志不等于测试失败，4 项付费门控跳过不计为通过。

完整回归后仅更新本记录，没有业务或测试代码收尾修正；最终提交前再次核对文档链接与差异。不将阶段 3 的历史结果当作本次验证，也不将本次受控模型测试当作阶段 5 真实模型验收。

## 未运行与后续

未调用真实付费 Agent、ASR、VLM 或 Embedding；真实素材／真实模型 A1 等验收留待阶段 5。本轮无前端改动，未运行前端类型检查、构建或浏览器验收；没有接通新 Agent 的预览／导出，没有宣称已生成成片。
