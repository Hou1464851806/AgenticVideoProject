# T31：在故障与权限边界下验收三条用户主线

状态：已发布为 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32)。T 编号保留为审阅映射。

## Parent

[MVP 总需求 #1](https://github.com/Hou1464851806/AgenticVideoProject/issues/1)

## Priority

P2；标签为 `priority:P2` 和 `ready-for-agent`。

## What to build

部署者用固定样本实际完成内容查询、讲话视频拆短视频及跨素材混剪，并能复现恢复与隔离结果。

本票包含该功能所需的持久化、业务接口、界面与必要验证，按用户操作闭环交付。新增入口必须验证项目权限，耗时任务沿用权威任务状态、当前尝试校验和幂等发布；不得把这些要求延后到最终验收。

## Acceptance criteria

- [ ] 人工标注样本与保留查询覆盖 AC-01 至 AC-32，真实浏览器完成三条主线；收集消息、出处、版本、任务与实际可播放产物作为依据。
- [ ] 跨服务中断覆盖数据库提交后、ACK 后启动前、产物上传后回报前，以及旧尝试迟到／取消竞争；无重复版本、产物或终态覆盖。
- [ ] 覆盖两账号所有新增素材、证据、消息、任务、索引通道及对象链接的授权；公开内容无私有凭据、路径或供应商内部推理。
- [ ] 核验隐藏历史素材仍可制作、缓存重建不重复分析、批量部分失败恢复和预算收束；遗留能力缺口逐项报告并修复，模拟或模型自评不能代替通过。

## Verification

本票是已有逐票验收的跨功能演练，不将前面任务的测试延后；按既定 AC 范围修复联动缺陷并保存结果。

## Blocked by

- [#6](https://github.com/Hou1464851806/AgenticVideoProject/issues/6)：断网、暂停与刷新后继续原上传
- [#7](https://github.com/Hou1464851806/AgenticVideoProject/issues/7)：取消或过期上传并查看残留清理
- [#8](https://github.com/Hou1464851806/AgenticVideoProject/issues/8)：超限或空间不足时阻止上传并解释原因
- [#13](https://github.com/Hou1464851806/AgenticVideoProject/issues/13)：在预算内复查候选，超预算先询问
- [#14](https://github.com/Hou1464851806/AgenticVideoProject/issues/14)：只恢复失败分析阶段并重建检索索引
- [#16](https://github.com/Hou1464851806/AgenticVideoProject/issues/16)：刷新或断线后恢复对话进度与结果
- [#17](https://github.com/Hou1464851806/AgenticVideoProject/issues/17)：停止对话任务并关联原需求重试失败
- [#19](https://github.com/Hou1464851806/AgenticVideoProject/issues/19)：长对话保留硬条件并分页查看完整历史
- [#22](https://github.com/Hou1464851806/AgenticVideoProject/issues/22)：手动调整片段并从任意历史版本继续
- [#23](https://github.com/Hou1464851806/AgenticVideoProject/issues/23)：对话替换指定片段并保留其他内容
- [#26](https://github.com/Hou1464851806/AgenticVideoProject/issues/26)：批量导出选定成片并展示部分完成
- [#27](https://github.com/Hou1464851806/AgenticVideoProject/issues/27)：按需求跨素材编排并导出基础混剪
- [#28](https://github.com/Hou1464851806/AgenticVideoProject/issues/28)：隐藏、恢复和安全删除素材
- [#29](https://github.com/Hou1464851806/AgenticVideoProject/issues/29)：清理过期预览并按需重新生成
- [#30](https://github.com/Hou1464851806/AgenticVideoProject/issues/30)：回收派生媒体后仍能核查历史证据
- [#31](https://github.com/Hou1464851806/AgenticVideoProject/issues/31)：查看分类存储、模型用量和费用预警

## Requirement coverage

总需求用户故事：US-85, US-86。

总需求验收：AC-01, AC-02, AC-03, AC-04, AC-05, AC-06, AC-07, AC-08, AC-09, AC-10, AC-11, AC-12, AC-13, AC-14, AC-15, AC-16, AC-17, AC-18, AC-19, AC-20, AC-21, AC-22, AC-23, AC-24, AC-25, AC-26, AC-27, AC-28, AC-29, AC-30, AC-31, AC-32（本票负责对应行为；跨功能复验另由 [#32](https://github.com/Hou1464851806/AgenticVideoProject/issues/32) 汇总）。

任务边界：执行既有逐票验证和跨功能演练。发现无法在单次新上下文内修复的重大实现缺口时，记录具体失败证据并单独拆出修复任务，不能把未通过场景标为完成。
