# 对话生命周期步骤 1：身份、迁移与共用契约

日期：2026-10-16（Asia/Shanghai）。实施基准 `ac4b1fb39b396d50ae8e031fbf777bca49c6aec9`，分支 `main`，起始工作区干净。依据系统临时目录交接与[完整设计](../conversation-lifecycle-browser-design.md)，本次仅实施七步顺序中的第 1 步，未交付整个阶段。

## 落地范围与取舍

V54 增量迁移复用现有表，不改历史 SQL、M2 草稿／条件哈希、校验规则或业务执行路径：

- `conversations.current_production_id` 指向同项目、同会话制作；`edit_productions` 保存所属会话、稳定制作 thread、编辑代次、当前条件／来源和最后发布草稿引用。CLI 制作保留无会话关联。历史由当前引用推导，冻结由现有 `plan_revisions.source_production_id` 推导，不增加执行状态机或重复保存状态。
- `conversation_execution_requests` 继续保存每个接纳任务的固定输入，新增版本、输入 hash、项目／会话／消息／turn、执行 thread／execution、scope、可空制作／代次、持久解析与公开结果。独立问答不创建制作；补答／重试的不同 task 可以关联同一 execution。不复制 attempts／lease；新协议和历史协议以 `schema_version` 区分。
- `edit_draft_validations` 增加来源 task／attempt／代次；`conversation_edit_drafts` 仅关联消息、制作及准确 validation／两种 hash／代次，不复制规范化内容。编辑后仍可保留旧投影，当前操作资格留给步骤 3 的事务判断。
- `conversation_questions` 增加制作、thread／execution／wait、代次与补答后继 task；约束原执行和后继任务的会话归属。原任务状态与问题交接事务在步骤 3／4 实施。

迁移中的复合外键约束项目／会话／制作／scope／thread、任务／attempt、校验／草稿消息的一致归属。当前资格、用户权限、合法校验、attempt／lease 的实时有效性、条件来源消息核验、并发锁序仍必须由后续边界事务实现，不能把结构约束当作业务接入完成。原有一次制作最多保存 V1 的约束继续生效。确认操作回执和媒体双来源约束随步骤 3 和 5 的具体事务落地，本步不提前放宽渲染外键。

## 共用传输契约

Java `AgentRunContracts` 与 Python `conversation_contracts.py` 无调度／图依赖。JSON 使用 camelCase；Python 属性为 snake_case，边界用 `model_validate` 接收 wire 值、`model_dump(by_alias=True)` 输出。M2 内嵌条件及来源内容仍使用原字段名，不递归改写 M2 数据。

- `RunInput`：`schemaVersion=1.0`，Java 指定的身份、当前原文、之前的有界历史、明确引用、当前制作摘要及待回答问题。当前消息单独传入，历史不重复追加它。身份包括 project／conversation／message／turn／task／thread／execution。
- 固定输入中的 thread／execution 是接纳理解时的身份。解析后选择制作 thread 或原挂起 execution 时，持久执行绑定记录在 execution request 对应列；不重写 `input_json` 或 `input_hash`。`RunResult.identity` 携带最终执行绑定，项目／会话／消息／turn／task 仍归属本次接纳。补答的 scope、预算与原 execution 恢复规则由步骤 2～4 实施。
- `ParsedInput`：QUERY／DISCUSS／DRAFT、INDEPENDENT／CONTINUE_PRODUCTION／NEW_PRODUCTION／ANSWER_QUESTION／CLARIFY、关联问题及条件增量。只有 DRAFT 可修改制作条件，只有 ANSWER_QUESTION 携带 questionId。模型不提供可信业务身份；传输程序核对并附加身份。条件来源由真实接纳消息生成。
- `RunResult`：ANSWER、WAITING_INPUT、DRAFT_READY、INCOMPLETE、FAILED、CANCELLED，含模式、公开文本、scope 工具回执／证据引用和按结果类型必需的载荷。DRAFT_READY 绑定持久校验与代次，WAITING_INPUT 绑定原 wait／执行；失败载荷说明原因与可恢复性。公开草稿的 messageId 由 Java 在执行接纳中分配，不能由模型生成。未知付费提交不恢复付费执行的具体裁定留在步骤 4。

所有声明字段必须出现，允许为空的对象显式使用 null；未知字段、类型强转、错误枚举、缺失载荷和不匹配的草稿／问题身份被拒绝。历史最多 100 条，各文本最多 16000 Unicode 码点，总 UTF-8 JSON 上限 1 MiB，与既有完整模型请求预算相符；实际模型请求仍须计入工具声明和证据。引用对象沿用既有结构，不另造引用体系；条件摘要允许尚未澄清完整的条件，因此不把它强制解析成完整 M2 Requirements。

固定输入与结果 hash 使用既有 `ToolJson.hash`／`fingerprint`：键排序、紧凑 UTF-8 JSON，保留数组顺序、显式 null 和 camelCase 键，包含全部固定身份与上下文；attempt／lease 在传输外层，不进入固定输入。任意 JSON 上下文中的数值限定为有符号 64 位整数，避免跨语言浮点序列化差异；对象字段名限定为 ASCII，避免 Java UTF-16 排序与 Python Unicode 码点排序的差异。文本值继续支持中文和 emoji，既有条件／引用字段名均满足该限制。这不改变 M2 算法。

共享夹具 `backend/src/test/resources/contracts/conversation-lifecycle.json` 包含中文、emoji、换行、控制字符／字面转义、64 位整数边界、独立问答／讨论、待补答、当前与已冻结制作、所有公开结果及无效输入。双方读取同一份样例与固定 hash，不在测试运行时重算期望 hash。

## 验证与审查

测试边界取自步骤 1 的退出标准：双方 wire 解析／哈希与真实 PostgreSQL 结构约束。没有运行浏览器或模型来代替这些边界。

开发过程：Python 契约专项通过；Java 契约与迁移专项最初暴露 V23 已有索引重名、Java JSON 节点整数类型的测试比较差异、SQL 测试夹具占位符错误，均已修正后重跑。迁移复用 V23 的任务项目唯一键，JSON roundtrip 通过序列化后再解析比较；不为这些问题增加运行时回退。

代码审查以实施前 HEAD 为固定点，对本次暂存差异做两项独立只读审查；审查时尚无新提交，使用 `git diff --cached ac4b1fb39b396d50ae8e031fbf777bca49c6aec9`，提交列表为空。

## Standards

初审与修正后复核均为 0 项发现。依据 AGENTS.md、GLOSSARY、双方 README 及 ADR-0007／0018／0019，传输契约和执行分离，迁移复用现有身份，边界校验与测试范围符合约定；未发现需处理的代码异味。

## Spec

初审 1 项 P2：任意 Unicode 对象键被双方接受，但 Java UTF-16 与 Python 码点排序存在差异，可能产生不同 hash。增加共享反例后 Python 测试先失败，再将新边界的 JSON 字段名统一限定为 ASCII；Unicode 值、既有 M2 算法不变。修正后独立复核为 0 项剩余发现，未发现遗漏或范围扩张。

两轴结论：Standards 0 项；Spec 初审 1 项 P2 已修复，最终 0 项。代码审查是静态检查，运行验证单独记录如下。

选择范围：新增契约／结构直接影响会话、制作、校验与来源关联，因此运行这些模块及读取工具、旧版本预览／导出入口的兼容回归。没有改动任务执行／认证／MQ 业务代码，新增 attempts 唯一键仅用于复合引用；不扩大到无关上传／分析模块。Python 新契约尚未挂入执行入口，运行契约及其直接依赖的 M2／Java 工具相关测试。

| 检查 | 最终实际结果 |
| --- | --- |
| Java 14 个相关测试类 | 135 项通过，0 失败／错误／跳过；含会话／恢复、读取工具、M2 校验／保存、既有版本预览／导出；Java 编译和测试编译通过 |
| 审查修正后的 Java 专项 | 6 项通过，0 失败／错误／跳过；13:07:12 完成，含新增 Unicode 键反例 |
| Python 6 个相关测试文件 | 修正后 64 项通过，0 失败／跳过 |
| Pyright | 新增契约及其测试，0 errors、0 warnings |
| Python compileall | 新增契约及其测试通过 |
| 数据库迁移 | PostgreSQL 18.6：保留 V53 历史 CLI 制作／旧任务输入后升级 V54，并执行 Flyway validate；相关既有集成测试从空库应用完整 54 份迁移通过 |
| 文档与差异 | 本次 3 份文档共 60 个本地链接可解析，`git diff --check` 通过 |

Java 135 项相关回归于 13:06:52 完成。在该次回归运行期间追加的审查修正仅涉及新契约的 ASCII 字段名校验和共享反例；未变更迁移／既有业务，后续单独重跑新契约与迁移专项。上述 Python 64 项、类型检查及 compileall 均在修正后执行。随后仅补充验证文档，没有再修改业务代码；不能把 135 项写成修正后整套重跑。

复现命令（仓库根目录）：

```sh
RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp -pl backend -am test \
  -Dtest=AgentRunContractsTest,ConversationLifecycleSchemaIntegrationTest,EditDraftContractsTest,ProductionSchemaIntegrationTest,EditPlanApiIntegrationTest,DraftValidationIntegrationTest,ReadToolsIntegrationTest,ToolJsonTest,ConversationApiIntegrationTest,ConversationRecoveryApiIntegrationTest,ModelRecordLimitApiIntegrationTest,InternalPreviewApiIntegrationTest,PreviewApiIntegrationTest,ExportApiIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -pl backend -am test \
  -Dtest=AgentRunContractsTest,ConversationLifecycleSchemaIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
agent-service/.venv/bin/python -m pytest \
  agent-service/tests/test_conversation_contracts.py agent-service/tests/test_editing_contracts.py \
  agent-service/tests/test_java_read_tool_contract.py agent-service/tests/test_tool_contracts.py \
  agent-service/tests/test_java_executor.py agent-service/tests/test_real_production.py -q
uvx --from pyright pyright --pythonpath agent-service/.venv/bin/python \
  agent-service/agent_service/conversation_contracts.py agent-service/tests/test_conversation_contracts.py
agent-service/.venv/bin/python -m compileall -q \
  agent-service/agent_service/conversation_contracts.py agent-service/tests/test_conversation_contracts.py
```

本轮最终验证无失败项；开发过程失败及审查修正已记录。所选测试无跳过项。未运行：Java／Python 全量非付费回归、后端打包、前端类型检查／构建、浏览器及真实模型／FFmpeg 媒体验收。本步不修改前端，也不是阶段收尾，后者仍按步骤 7 与 AGENTS.md 单独执行；没有新增付费调用。既有会话测试仅作结构兼容回归，不能证明新版浏览器入口已接通。

## 后续交接

步骤 2 使用本契约调整 Python 公共入口，保留原 M2 工具 Schema、预算与付费回执。步骤 3 才开放新 Java 接纳／发布／确认事务，须实现设计中的统一锁序和过期校验写入保护；V54 提供的 FK 不替代实时授权。步骤 4 才接 Worker／MQ。旧浏览器与 Worker 开关保持关闭。
