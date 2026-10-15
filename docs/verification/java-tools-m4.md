# M4 实施与验证记录

当前已完成步骤 1～3；最新范围、验证和下一步见本文末尾的步骤 3 记录。

日期：2026-10-15（Asia/Shanghai）。本轮仅实施 [最终设计](../java-tools-m4-design.md)的步骤 1，不是整个 M4 阶段交付。基准 `2aca5d1`，分支 `main`，起始工作区干净。已核对系统临时目录中的 M4 设计交接。

## 步骤 1 的交付范围

- 定义 snake_case 的 PreviewRequest、PreviewResult、列表及产物／播放／错误传输值；Java、Python 共同解析 `backend/src/test/resources/contracts/m4-preview.json`。Python 本步只提供传输模型，具体 HTTP 客户端留到步骤 3。
- 新增 `POST /internal/v1/productions/{requirement_id}/previews` 和同路径 GET 列表。沿用现有内部 Bearer 认证，不启用旧 Worker 协议。
- 从 `ProductionSaves.result` 取得权威保存版本和 output_key→output_id；单输出可省略选择，多输出须指定。未保存拒绝，无隐式保存；不接收版本、输出 UUID、任务或渲染内容替换。
- 复用原预览输入冻结、项目锁、任务与 Outbox 创建代码；新建返回 202，复用返回 200。内部普通提交遇到同输入的失败／取消记录返回当前结果，不沿用旧公共接口的自动新建行为。旧公共接口仍保持原语义。
- 列表保留稳定创建顺序和 output_key，不生成播放地址；内部结果仅关联 PREVIEW／PREVIEW_RENDER。

本步没有开放 REQUEST_ID 详情、取消、重试三个端点，没有新增 CLI 命令。固定重试后继、完整复用选择及源文件条件、详情实时产物检查／签名播放访问和存储故障细化由步骤 2 完成。提交回执中的产物状态来自现有发布记录；不能代替步骤 2 的详情可用性检查。共用契约中的成功播放样例只验证格式，不代表本步提供播放接口。

## 验证范围和结果

依据根 AGENTS.md，本步修改预览接纳及内部传输，回归 render 全模块、M2 校验／保存与直接使用预览接纳的本机媒体链路；未修改共享认证、MQ、任务执行或数据库契约，不扩大为整个服务／整个 M4 全量验收。Java 编译包含在相关 Maven 测试中；Python 沿用语法编译，不引入新类型检查体系。

TDD：Python 共用契约测试先因缺少模块失败；Java 共用契约测试先因缺少类型编译失败；真实 M2 保存→内部列表测试先因接口不存在返回 404。随后实现使其通过。开发中修正了 Java JSON 整数节点类型导致的契约断言差异。

通过记录：

| 检查 | 结果与实际范围 |
| --- | --- |
| `mvn -B -ntp -pl backend -am test '-Dtest=PreviewContractsTest,InternalPreviewApiIntegrationTest,PreviewApiIntegrationTest,ExportApiIntegrationTest,RenderCapacityIntegrationTest,EditDraftContractsTest,ProductionSchemaIntegrationTest,EditPlanApiIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` | 47 项通过，0 失败／错误／跳过；覆盖 render 和 plan。审查后的键校验修正另见下行，未把此旧结果当作修正后的完整复跑 |
| `mvn -B -ntp -pl backend -am test '-Dtest=PreviewContractsTest,InternalPreviewApiIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` | 最终代码 9 项通过，0 失败／错误／跳过；包含新增真实 M2 空白键选择回归，Java 主代码与测试重新编译通过 |
| `DraftValidationIntegrationTest,LocalMediaIntegrationTest` | 30 项通过，0 失败／错误／跳过；覆盖 M2 校验／保存／Python受控接入及真实 MQ／本机 FFmpeg 的既有调用链 |
| `agent-service/.venv/bin/python -m pytest agent-service/tests/test_preview_client.py agent-service/tests/test_editing_contracts.py -q` | 最终代码 12 项通过，0 失败／跳过；共用 M4 契约及 M2 契约／哈希回归 |
| `agent-service/.venv/bin/python -m compileall -q agent-service/agent_service/preview.py` | 通过；项目未引入额外 Python 类型检查器 |
| `git diff --check`、改动 Markdown 的本地链接存在性检查 | 通过；140 个本地链接可解析 |

测试选择核对：首轮 Maven 包名通配没有选中 render／plan，仅执行了上述 30 项直接调用链测试；发现后改用显式类名补齐 47 项，以上分别记录实际范围。

审查采用 code-review 两个独立子代理，以 `git diff 2aca5d1` 审查本轮变更。Standards：0 项发现。Spec：初审发现 1 项 P2——新增纯空白 output_key 拒绝规则收窄 M2 合法键范围。已移除 Java/Python 额外限制，补共用样例及真实 M2 双输出保存→选择→列表回归；先复现 Python 失败，再修正为通过。复审确认关闭，最终 0 项未解决。审查后仅改该边界校验、测试样例及验证文档，最终 9 项 Java／12 项 Python 补跑覆盖修正。

最终失败项：无。跳过项：上述选定测试均为 0。未运行：整个 Java／Python 非付费全量、后端打包、前端与浏览器回归（无前端改动）、付费模型，以及 M4 CLI→新内部入口→真实 M2 素材视频端到端验收。这些属于后续步骤或阶段收尾；受控本机媒体回归不能替代 M4 真实视频验收。

## 步骤 1 后续交接（历史）

从步骤 2 继续，完善预览业务规则和 REQUEST_ID 端点。尤其核对完整 Q6、Q11、Q12 规则：当前共用接纳仍先构建源文件指纹并只查看最近相同 cache_key 的请求，步骤 2 须补全复用候选／历史目标选择；不得把本步有限接入当成全部业务规则完成。步骤 3 再接 Python HTTP 客户端与 CLI；步骤 4 复用 M2 真实素材验收；步骤 5 完成阶段全量与打包。


## 步骤 2：预览业务规则

日期：2026-10-15（Asia/Shanghai）。实施基准 `131bf1bc577d93433a6cf55d0b0f97ae57731ba9`，分支 `main`，起始工作区干净。依据最终设计与上述步骤 1 交接，本轮仅交付步骤 2，不是整个 M4 阶段交付。

### 范围与成功标准

- 内部普通接纳在项目锁事务内选择全部同输入候选，能够越过较新的失败记录找到运行任务或可用产物；取消中复用原任务。无法复用时按同版本／输出／LOW_360 找回最近历史，不因源指纹变化或素材失效而自动重渲染。只有新建任务才要求素材有效、源文件存在且大小匹配，并检查现有空间预算。
- 新增 `GET /internal/v1/previews/{request_id}`、`POST /internal/v1/previews/{request_id}/cancel` 和 `/retry`。三个端点均限定 PREVIEW／PREVIEW_RENDER，不允许访问或取消 EXPORT；沿用内部认证。
- 固定重试后继复用 `preview_intents`。项目锁内先读服务端专写的 `request_json.retry_of`；没有后继才检查原请求可重试性，再复用同输入工作或新建任务。后继关联与任务／Outbox 接纳同事务提交；后继后来失败也仍重放它，必须重试后继本身才能继续。
- `retry_of` 是内部回执结构，不接受客户端填写。采用独立的服务端 JSON 结构和随机 opaque key，避免旧公共接口任意 `Idempotency-Key` 与程序构造的 `retry:A` 冲突；公共键及原回执重放语义保持。无需新增迁移。
- 详情一次 JOIN 读取请求发布身份及任务结果，只展示已发布产物；产物模块一次存储 HEAD 后组合状态与可选的 300 秒签名播放地址。文件缺失／大小不符标记 UNAVAILABLE，保留历史 SUCCEEDED；REMOVED 同样允许重试。超时、403、500 返回 503／PREVIEW_STORAGE_UNAVAILABLE，不写成文件失效。
- 取消沿用现有 TaskExecution：排队直接取消，运行中返回 CANCEL_REQUESTED，执行者退出确认后才为 CANCELLED；成功终态取消不删除产物。预览操作不修改保存方案。

测试边界沿用设计已约定的内部／公共 HTTP 接口。使用真实 PostgreSQL 与 Silo，任务领取／发布／退出通过现有 TaskExecution 驱动受控夹具；存储超时／权限／服务错误只在 MultipartStorage 系统边界注入。并发普通提交和并发重试各验证只有一次新建及同一请求身份，列表确认完整历史；不是通过断言私有方法或内部调用次数验证。

### 分层验证

范围依据：修改集中于 render 接纳、内部传输及产物访问；没有修改共享认证、MQ、任务状态机、恢复实现或跨语言传输契约。运行 render 全模块与直接受影响的 TaskApi／LocalMedia、M2 草稿校验保存／方案读取回归。Java 主代码与测试编译随 Maven 检查完成。本步不使用 implement 默认的阶段全量要求替代根 AGENTS.md 的分层约定。

TDD 记录：

- 固定后继测试先因 `/retry` 不存在返回 404 失败，实现后通过。
- 素材指纹变化并失效后的普通提交先返回 409，未找回历史，测试失败；改为先选择可复用记录／历史、新建再验证源条件后通过。
- 审查新增公共键冲突测试，先复现 retry(A) 返回 A／200，而非创建 B／202；隔离内部回执结构后通过。

| 检查 | 结果与实际范围 |
| --- | --- |
| 单条固定后继测试 | 1 项通过；覆盖运行时禁止强制重渲染、取消后重试、后继失败的当前状态重放、继续 retry(B) |
| `InternalPreviewApiIntegrationTest` | 15 项通过，0 失败／错误／跳过；覆盖并发、候选／历史、详情／播放、源文件、取消、身份及故障边界 |
| 审查修正后的 `PreviewApiIntegrationTest#publicIdempotencyKeysCannotBecomeInternalRetryReceipts,InternalPreviewApiIntegrationTest` | 16 项通过，0 失败／错误／跳过；含公共键冲突先红后绿与内部完整回归 |
| 最终模块及直接调用链回归（下列 9 类命令） | 82 项通过，0 失败／错误／跳过；render 34、TaskApi 2、LocalMedia 5、M2 草稿校验保存 25、方案接口 16；主代码与测试编译通过 |
| `git diff --check`、改动 Markdown 本地链接 | 通过；89 个本地链接可解析 |

最终回归命令（仓库根目录）：

```sh
mvn -B -ntp -pl backend -am test \
  '-Dtest=PreviewContractsTest,InternalPreviewApiIntegrationTest,PreviewApiIntegrationTest,ExportApiIntegrationTest,RenderCapacityIntegrationTest,TaskApiIntegrationTest,LocalMediaIntegrationTest,DraftValidationIntegrationTest,EditPlanApiIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

审查修正前曾运行 render + TaskApi + LocalMedia，共 40 项通过，0 失败／错误／跳过；该结果不作为最终代码验证。最终回归在两轴复审及全部主代码修正后运行，之后仅补充本文的验证结果。

### 代码审查

按 code-review 技能以 `git diff 131bf1bc577d93433a6cf55d0b0f97ae57731ba9` 做两轴独立子代理审查。

- Standards：初审及复审均 0 项问题，依据 AGENTS.md、GLOSSARY.md、ADR-0017。
- Spec：初审发现 1 项 P1——公共幂等键可占用 `retry:A` 回执，导致固定后继错误；已按上文修正并补跨入口回归。复审 0 项未解决问题。

最终失败项：无。最终跳过项：0。上述 TDD 红测均为预期失败，已修正并包含在最终通过结果中。最终回归后仅更新验证文档，没有主代码收尾改动。

### 步骤 2 后续交接

从步骤 3 继续实现 Python 具体 HTTP 客户端、五个 CLI 命令和只读保存身份恢复。Python 目前仍只有步骤 1 的传输模型；CLI 尚不能调用预览。重试回执按 `request_json.retry_of` 查询，不再按 `idempotency_key='retry:A'` 查询；新客户端不需要管理这个内部关联。

未运行：Java／Python 整体非付费全量、后端打包、前端／浏览器、付费模型、M4 CLI 经真实 M2 保存素材到视频的端到端验收、真实 Java 进程终止恢复验收。前两项属于步骤 5 阶段收尾；CLI 和真实视频／故障联调属于步骤 3、4。本步的真实 PostgreSQL／Silo HTTP 夹具与既有 MQ／FFmpeg 回归不能替代 M4 真实素材及人工观看验收。


## 步骤 3：Python 客户端与 CLI

日期：2026-10-15（Asia/Shanghai）。实施基准 `072267f920f175e5c5b09ad2865071bbeff3eb3f`，分支 `main`，起始工作区干净。依据最终设计及本文步骤 2 后续交接，仅交付步骤 3，不是完整 M4 阶段交付。

### 范围与成功标准

- `preview.py` 实现具体 `PreviewClient`，沿用 httpx、内部 Bearer 认证及既有 snake_case 模型，直接调用五个预览端点；恢复保存身份时只读现有 M2 保存结果端点。URL 身份按 UUID 验证，选择沿用 M2 可接受的 output_key，不接收版本、输出 UUID 或渲染参数替换。
- CLI 新增 `preview THREAD [--output KEY]`、`preview-list THREAD`、`preview-status/cancel/retry REQUEST_ID`。THREAD 命令使用现有 inspect 的 production 关联；保存响应未写回时，只对已提交的 save_request 查询服务端保存结果，不调用 save／resume，不回写 checkpoint。
- 必要的小范围调整：`Agent._view` 只读返回已有 `save_request`，以区分未保存草稿与保存待核对；未改图节点、执行、保存流程、工具目录、模型、预算或哈希。REQUEST_ID 命令在 Agent／脚本模型构造前分派，不创建 data-dir、不打开 SQLite，不要求模型凭据。
- JSON 保留 Java 的 task_status、独立产物状态和播放链接；提示写入 stderr，不等待、不下载、不自动播放。普通失败／取消／产物失效要求显式重试；合法取消退出 0，其余提交／详情／重试的失败终态或不可用产物退出 1。旧命令退出码保持。
- HTTP／网络／协议错误明确非零，使用命令级 error 而非伪造任务失败。HTTP 传输不跟随重定向；提交／取消／重试 POST 不自动重试，响应丢失提示按列表／原请求核对。取消提示如实说明共享任务影响及 CANCEL_REQUESTED 尚未确认停止。

### 分层验证

测试边界沿用设计已约定的客户端 HTTP 与 CLI。客户端通过 httpx 系统边界注入故障；CLI 使用本机真实 HTTP 服务与经 Agent 公共 send／save 生成的 SQLite 制作记录。保存丢响应由外部 HTTP 边界模拟；通过 inspect 前后相等核对 SAVED、waiting、制作信息与预算未变化。未 mock Agent、预览客户端或图内协作者。

范围依据：新增 Python 传输与 CLI，另对共享只读视图增加一个字段。运行预览模块、M2 契约／制作／Java 执行器，以及原 Agent 场景、恢复／预算、审查回归、验收退出码；Java 运行共用预览契约、内部接口及直接受影响的 M2 HTTP／Python CLI 校验保存闭环。未改 Java 业务实现、共享任务、MQ、认证或数据库，不扩大到整个服务全量。Python 语法编译沿用现有方式，无新增类型检查体系。

TDD：客户端首次提交测试因缺少 PreviewClient 失败，网络／协议／HTTP 错误测试因缺少错误类型失败；实现后通过。CLI REQUEST_ID 首测因命令不存在失败，随后通过且未创建 data-dir；THREAD 首测因命令不存在失败，随后实现只读制作绑定使其通过。

首轮相关回归：Python 137 项通过，0 失败／跳过；Java 42 项通过，0 失败／错误／跳过（PreviewContracts 2、InternalPreviewApi 15、DraftValidation 25），含真实 PostgreSQL 的既有 M2→Python CLI 保存集成。Python compileall 和 git diff --check 通过。此后仅整理本轮测试的导入／未使用参数与文档，最终审查及补跑结果在下文记录。

相关回归命令（仓库根目录）：

```sh
agent-service/.venv/bin/python -m pytest agent-service/tests/test_preview_client.py agent-service/tests/test_preview_cli.py agent-service/tests/test_real_production.py agent-service/tests/test_java_executor.py agent-service/tests/test_editing_contracts.py agent-service/tests/test_agent_scenarios.py agent-service/tests/test_resume_and_limits.py agent-service/tests/test_review_regressions.py agent-service/tests/test_live_acceptance_report.py -q
mvn -B -ntp -pl backend -am test '-Dtest=PreviewContractsTest,InternalPreviewApiIntegrationTest,DraftValidationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
agent-service/.venv/bin/python -m compileall -q agent-service/agent_service/preview.py agent-service/agent_service/cli.py agent-service/agent_service/runtime/runner.py
```

### 代码审查与最终结果

按 code-review 技能，以固定基准 `git diff 072267f920f175e5c5b09ad2865071bbeff3eb3f` 审查本轮未提交变更（新测试以 intent-to-add 纳入差异），由两个独立子代理分别完成：

#### Standards

0 项需修正问题。符合 AGENTS.md 的边界校验、最小实现、分层验证要求及 ADR-0017 的职责分界；未发现 GLOSSARY.md 术语偏差。端点方法的少量相似边界处理无需新增抽象。审查未另行运行测试。

#### Spec

0 项可操作发现。五命令路径、认证、选择、只读保存身份恢复、REQUEST_ID 独立性、错误与退出码符合步骤 3；`save_request` 的只读展示为恢复所需，没有扩展图执行或保存行为。真实媒体、进程恢复及阶段全量按步骤 4／5 验收。审查为静态审查。

两轴各 0 项未解决问题，无审查后主代码修正。最终检查：

| 检查 | 最终结果与代码对应关系 |
| --- | --- |
| Python 相关模块／原 CLI 与 M2 调用链 | 137 项通过，0 失败／跳过；此后没有主代码变更，仅整理本轮测试导入／未使用参数及文档 |
| Java 预览契约、内部接口、M2 校验保存 | 42 项通过，0 失败／错误／跳过；此后无主代码变更 |
| 最终 `test_preview_client.py test_preview_cli.py` | 53 项通过，0 失败／跳过；覆盖整理后的最终测试代码 |
| 最终 compileall | 三个修改的 Python 主代码文件通过；不新增类型检查体系 |
| CLI 子进程 `--help`、git diff --check | 通过；五命令可由真实模块入口解析 |
| 修改文档的本地链接检查 | 94 个链接可解析；结合差异检查内容及步骤边界 |

最终失败项：无。最终跳过项：0。预期 TDD 红测已修正为通过。最终测试后只更新本验证记录，不改主代码；未将上述相关回归称为阶段全量。

### 步骤 3 后续交接

从步骤 4 继续，复用 M2 已分析素材及真实保存方案，通过新 CLI 执行真实视频与响应丢失、取消、文件失效、Java 进程重启恢复验收。现有测试不替代真实 CLI→MQ→FFmpeg 的媒体验收或人工观看；步骤 5 再对 Q1～Q13 做阶段收尾、最终 Java／Python 完整非付费回归与后端打包。

未运行：整个 Java／Python 非付费全量、后端打包（步骤 5）；M4 真实视频／故障与进程重启端到端验收（步骤 4）；付费模型；前端类型检查／构建与浏览器回归（本步无前端变更）。Python 客户端不管理 retry_of 或 Java 重试回执，不新增本地预览任务库。
