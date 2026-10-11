# #8 上传限额与实际存储空间

状态：实现、完整验证与两项独立评审完成。

## 测试边界与实现

沿用已确认的公开 HTTP 接口、真实 PostgreSQL / Silo、Compose 浏览器及真实媒体 Worker。配置限额后验证边界及并发预留，实际签名 PUT 验证服务端声明与真实字节的区别。

- `UPLOAD_MAX_FILE_BYTES` 默认 2147483648（2 GiB），`UPLOAD_MAX_PROJECT_BYTES` 默认 5368709120（5 GiB），`UPLOAD_MAX_PROJECT_ASSETS` 默认 10。文件限额不能超过当前 8 MiB 分片的 10000 片协议上限。
- 项目行锁保护项目字节与名额；数据库事务级全局锁保护跨项目的未来写入预算。幂等重放复用原身份，不重复预留。
- Compose 把 Silo、媒体 Worker、Agent 工作区卷只读挂载到业务 API；使用 `Files.getFileStore(...).getUsableSpace()` 实测三个卷，采用最小可用值，避免把同一 Docker 文件系统的可用量累加。已有原始文件、派生文件、工作区和输出实际占用已经体现在可用空间中。
- `CAPACITY_MIN_FREE_BYTES` 默认 512 MiB；`CAPACITY_MAX_RESERVED_BYTES` 默认不额外限制，用于可配置的未来写入预算上限。非 Compose 部署必须将 `CAPACITY_PATHS` 指向实际对象与工作区文件系统；本地开发默认临时目录，不能将宿主临时目录当作远端 Silo 的容量证明。
- 未完成上传持久预留 `2 × 声明大小 + 300 MiB`，覆盖 multipart 组装／素材副本以及一小时 mono 16k PCM 的派生和处理副本、暂存余量。取消后保留到实际清理核对完成。完成后，媒体准备及语音分析未终结时继续保留；终结后以实际卷占用为依据。
- 排队及执行中的预览／导出持久预留素材副本、按规格和时长估算的中间视频、PCM 音频、两份编码输出和 64 MiB 暂存。执行资格失效、取消尚未退出时不释放；终态不再预留未来写入，已写文件仍计入实际卷占用。
- 创建、分片授权、完成阶段复查服务端限额及容量；实际列片、大小、序号和总量核对后才允许发布。发现超过声明／分片规则的真实片立即撤销会话并进入 #7 的持续清理，阻止继续签发与发布。
- Worker 开始下载、探测或渲染前，通过当前 attempt / 租约认证的 `space-check` 复查共享池，并再次检查自己的工作区。低空间返回 `STORAGE_SPACE_LOW`；真实媒体任务显示 `MEDIA_SPACE_LOW` 和清理／扩容操作提示。60 分钟限制继续由真实 FFprobe/FFmpeg 验证执行。

## 已取得的 RED → GREEN 证据

- 1024 字节配置原先仍接受 1025：`/tmp/agenticvideo-issue8-file-red.log` → `file-green.log`。
- 实测可用空间低于安全余量原先仍创建：`space-red.log` → `space-green.log`。
- 两个项目原先同时占用同一未来媒体预算：`global-red.log` → `global-green.log`。
- 19 字节授权真实写入 20 字节，原先拒绝但未清理：`part-red.log` → `part-green.log`。
- 创建后卷不可用原先仍签发：`recheck-red.log` → `recheck-green.log`。
- 排队预览原先不预留：`render-red.log` → `render-green.log`。
- 执行前空间接口及可识别拒绝原因：`execution-red.log` → `execution-green.log`、`space-code-red.log` → `space-code-green.log`。
- 真实 Worker 在安全余量不足时原先仍发布有效素材：`worker-red.log`。修复后三个真实 Compose 场景通过：`limits-green.log`（浏览器限额/低空间、真实探测拒绝、真实预览/导出拒绝）。

上述简写日志均位于 `/tmp/agenticvideo-issue8-` 前缀下。

## 残余窗口

固定 Silo 实测允许短期预签名分片 URL 写入超过声明长度。应用限额与未来写入估算不是文件系统的物理硬额度：已签发 URL 可重用至到期，已开始的 PUT 也可能在到期后结束；外部写入与空间检查之间仍存在竞争。只签发有限的三片批次、最长 300 秒授权，后续授权／登记／完成依据真实列片拒绝超额，并撤销会话。沿用 #7 的授权及操作排空期限、逐次 abort/delete/HEAD/list 验证和清理完成后的持续巡检，收敛晚到残留；未证明物理清空前不释放占用。若需要不可绕过的物理边界，应在对象存储／文件系统层配置独立配额。

## 独立评审

固定基线 `6a7b8834215dc16a14d4ba8c2984fbf0f0eafb35`。

- Standards：PASS，硬违规 0；原有测试客户端重复建议已通过 `UploadTestApi` 收拢，二次评审 PASS，无待修问题。
- Spec：PASS，未发现缺失、范围扩张或错误。
- 额外复查项目外键与全局锁：完成阶段的准入事务在外部 Silo 操作前已经提交；随后素材/任务发布使用另一事务，未同时持有全局锁，不构成假设中的锁闭环。保持外部存储操作不占用该准入事务。

## 测试隔离修复

最后全量回归曾因宿主临时目录实测仅剩约 2.4 GiB、类内前序测试留下的媒体未来预算累计而正确拒绝后续创建。`UploadApiIntegrationTest` 增加公开接口 teardown：取消本测试自己的未完成上传并等待 Silo 核对清理；已传输完成的素材仅取消未被 Worker 消费的验证任务。未降低安全余量或绕过真实空间判断。14 项上传竞争／清理测试重新通过（`/tmp/agenticvideo-issue8-isolated-uploads.log`），该修复独立 Standards 再次 PASS。

## 最终验证

- 完整 Playwright 浏览器回归：**48 项通过**（12.9 分钟），含真实百炼 ASR、DeepSeek、Silo、媒体 Worker 与新容量场景；`/tmp/agenticvideo-issue8-first-browser.log`。生产代码在本轮开始后没有改变。
- Vue 类型检查与前端构建通过：`/tmp/agenticvideo-issue8-frontend-build.log`。
- 三个 Compose 镜像完整构建通过：`/tmp/agenticvideo-issue8-final-images.log`。
- 最终 Maven `verify`：**61 项 Java 集成测试通过，0 失败、0 错误、0 跳过**（4 分 53 秒），后端与 Worker 构建通过；`/tmp/agenticvideo-issue8-final-verify.log`。
- 正常 Compose 配置恢复，所有服务运行正常；`/tmp/agenticvideo-issue8-normal-services.log`。
