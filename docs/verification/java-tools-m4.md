# M4 实施与验证记录

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

## 后续交接

从步骤 2 继续，完善预览业务规则和 REQUEST_ID 端点。尤其核对完整 Q6、Q11、Q12 规则：当前共用接纳仍先构建源文件指纹并只查看最近相同 cache_key 的请求，步骤 2 须补全复用候选／历史目标选择；不得把本步有限接入当成全部业务规则完成。步骤 3 再接 Python HTTP 客户端与 CLI；步骤 4 复用 M2 真实素材验收；步骤 5 完成阶段全量与打包。
