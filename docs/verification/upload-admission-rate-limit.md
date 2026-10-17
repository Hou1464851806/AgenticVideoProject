# 上传准入令牌桶验收

日期：2026-10-17（Asia/Shanghai）。实施基线：`77de143bd262106d3aaa174ba56dd2bc587bb745`。需求：[上传准入 Spec](../upload-admission-rate-limit-spec.md)。本记录随最终实现提交；全部非付费阶段门槛已通过。

## 实现与边界

专用 `UploadAdmission` 与 Lua 使用 Redis TIME、用户级 key、部分令牌余额和原子读改写。默认容量 3、每 60000 ms 补充一个；访问后 TTL 为容量乘周期。调用在用户／项目 SQL 锁内、全局容量锁前。合法幂等重放不访问 Redis；扣减后不补偿。429 带毫秒等待及向上取整秒级 Retry-After；503 无恢复时间。前端保留 code/details，停止两类准入错误的快速重试，仍允许手动继续。

Spring Data Redis／Lettuce 使用每次操作独立连接、关闭自动重连和断线排队；连接／命令超时各 300 ms。脚本没有业务重试；EVALSHA 仅在明确 NOSCRIPT 时改用 EVAL。连接／超时和 Redis OOM 映射 503；脚本或状态类型错误继续暴露。单实例部署、无费用预算及全项目多实例承诺。

独立 Compose Redis 固定为 `7.4.6-alpine@sha256:3b73847e72874be07e6657b129a94761662b79bc0f679273757d4218573b2a98`。`docker buildx imagetools inspect` 确认 ARM64 清单；实启输出 aarch64。无宿主端口、无快照／AOF，32 MiB／noeviction，容器 64 MiB／0.5 CPU。API 等待 Redis 健康启动；运行中故障仅拒绝新上传准入。配置更改按重启生效，验收使用新用户或清空测试桶。

客户端取舍参考 [Lettuce Client Options](https://redis.github.io/lettuce/advanced-usage/#clientoptions)，Redis 原子脚本参考 [Redis scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/)。实际行为由下列真实 Redis 测试验证。

## 验收覆盖

| Spec | 证据 |
| --- | --- |
| A01–A04 | `UploadAdmissionTest` 验证默认 3 次、两个组件 24 请求并发、部分时间累计、用户隔离；HTTP 并发跨项目验证同用户共享 |
| A05–A06 | HTTP 合法重放、内容／writer／项目冲突、非法参数／归属／文件限制／项目字节及数量配额；检查剩余准入机会 |
| A07 | HTTP 可控 SQL 插入触发器失败、容量边界故障注入和取消后继续耗尽桶，验证不返还 |
| A08–A09 | HTTP 429／503 后上传、预留、任务数量不变，未调用对象存储初始化；错误码、retryable、毫秒／秒等待及无假定恢复时间 |
| A10–A11 | 真 Redis 暂停制造超时、切换监听端口制造连接拒绝、恢复后新请求成功；TTL、重复拒绝、状态删除和内存不足 |
| A12 | 对象存储初始化可控失败后，在 Redis 暂停时使用同 key 恢复已有上传，不重复扣减 |
| A13 | `upload-admission.spec.ts` 分别验证 429／503 保留提示、超过原快速重试时间后仅一次创建、手动重试同 key、分片失败后的恢复和重试 |
| A14 | 完整 Java 非付费回归包含认证限流、既有上传／分析／任务测试；Python 无契约或实现改动 |

非专项 Spring 测试通过测试初始化器连接真实共享 Redis，显式使用容量 10000；专项 HTTP 测试指定 3，组件测试使用独立 Redis。未通过关闭限流适配旧测试。没有数据库迁移，无 Python／模型出口／MQ 变更。

## 验证过程与结果

开发先运行新组件测试，确认未实现时编译失败；实现后组件 6 项通过。HTTP 新拒绝用例在未接入时收到 201 而失败；接入后验证契约。浏览器新用例在原快速重试行为下失败，修正后 2 项通过。原有上传配额基线 2 项通过。

发现并修正测试夹具：Docker stop/start 会改变随机宿主端口，导致后续测试连旧端口。初轮 HTTP 恢复失败，初轮上传模块回归受到连带影响并中止，不计为通过；改为同一容器内切换监听端口后，连接拒绝／超时／恢复单条测试通过。后续使用修正后的夹具重新回归。

| 检查 | 结果 |
| --- | --- |
| `docker compose config --quiet` | 通过 |
| `docker compose up -d --wait redis`；CONFIG GET；uname | 通过，健康、aarch64，32 MiB／noeviction／无持久化；未重建共享 API 或提交上传到共享部署 |
| `npm --prefix frontend run build` | 通过，含 vue-tsc 类型检查 |
| 当前有效浏览器入口 + 新限流场景 | 通过，67 项；受控 HTTP，不调用模型 |
| `mvn -B -ntp -pl backend -am test -Dtest='Upload*Test' -Dsurefire.failIfNoSpecifiedTests=false` | 通过，34 项；其后仅补强同名 HTTP 测试的断言，最终由完整 Java 回归覆盖 |
| 规范／Spec 两轴审查 | 通过；两个独立审查均为 0 项 actionable findings |
| `RUN_LIVE_JAVA_ANALYSIS=0 mvn -B -ntp -Dlifecycle.model=false verify` | 通过，254 项：250 通过、4 项付费分析跳过，0 失败／错误；耗时 9 分 53 秒 |
| `agent-service/.venv/bin/python -m pytest agent-service/tests -m 'not live' -q` | 通过，235 项 |
| `mvn -B -ntp -pl backend -am package -DskipTests` | 通过；仅验证打包，不替代测试 |

浏览器命令：

```bash
E2E_BASE_URL=http://127.0.0.1:5173 npm --prefix frontend run test:e2e -- results-usage-workspace.spec.ts production-workspace.spec.ts conversation-lifecycle.spec.ts workspace-navigation.spec.ts frontend-redesign-baseline.spec.ts assets-workflow.spec.ts upload-admission.spec.ts --grep-invert 'real existing'
```

本次应用／测试代码相对基线的暂存差异 SHA-256：`02271bcd2fec99ae19a8714480fd908a6221c907889366d3e9208ee3d5593d1b`（`git diff --cached -- backend frontend compose.yaml | shasum -a 256`）。最终回归后仅补写本文证据，不改变应用或测试代码。

测试范围依据：开发先覆盖准入、上传事务、错误投影和客户端重试。完整交付再按 AGENTS.md 运行 Java／Python 全部非付费回归及打包、前端构建和当前有效浏览器入口。真实付费模型与共享部署上传完成验收未运行，不属于本次门槛；历史退役 E2E 不作为默认门槛。未改动用户原有的其他待提交文档。


## 最终审查与状态

- Standards：0 项 actionable findings；符合根 AGENTS.md 的最小实现、边界校验与分层回归要求。
- Spec：0 项 actionable findings；未发现缺失、实现错误或范围膨胀。
- 最终 Java 完整回归覆盖了上传模块回归后补强的 HTTP 断言；其后未修改应用／测试代码，仅更新验收文档。上述差异哈希保持一致。
- 最终验证无失败。开发期间的预期红灯、夹具故障及中止已在前文记录，未当作通过。跳过项为 4 项付费 Java 分析；未运行真实付费模型、共享默认部署上传完成以及历史退役浏览器入口。
- 工作区原有 `docs/README.md` 修改及其他未跟踪文档保留，不纳入此次实现提交。
