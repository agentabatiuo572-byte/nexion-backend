# 客服增强后端 bulk 交接

权威规格：S1 `fceac7fc4c8451772f63a976c0b2865734248e17`，`docs/specs/support-enhancements-20261001/`。实现起点为 P1 `9324c8018592af2b2b15a739db7037a6f495d534`，交付分支 `codex/cs-enhance-core-20261001`。

范围为 C5/C6 与 B01–B13 的服务端义务。后台两入口与页面操作属于 P3，正式客户端属于 P4，三仓整体属于 P5；本仓证据不能替代这些阶段或生产部署。

## API 与冻结集合

统一前缀 `/api/admin/content/support-workbench/bulk`：

- `POST /preview`：filters、customerIds、excludedIds、selectionMode；支持 SINGLE/PAGE/CROSS_PAGE/EXPLICIT/ALL_FILTERED，返回 selectionId、原归属、准确人数、排除原因和 UTC 时间。预览持久冻结集合，不发送消息或记录维护执行。
- `POST /`：selectionId、显式 intent、kind/content、skuId/linkTarget/assetId、reason，要求 `Idempotency-Key`。提交后集合和内容不因新客户、筛选变化或重试扩充。
- `GET /`、`GET /{batchId}`、`GET /{batchId}/recipients`：批次与逐客结果分页；每次按当前数据库权限投影。原发起者可保留汇总，失权客户的明细、内容及附件引用受到限制；新顾问不能接续原批次。主管可审阅，不能代发。
- `POST /{batchId}/cancel`、`POST /{batchId}/retry`：expectedVersion/reason 与 key；原 key 不换请求，取消只影响未提交消息项。
- `POST /attachments`：multipart file/clientUploadId 与 key；仅返回私有批次资产 metadata，无共享读取 URL。上传 ID 与原 key 固定绑定；同 ID 换 key 返回冲突，重试须保留原 key。
- 原 `/support-workbench/commands/{key}` 接入 bulk 创建、取消、重试收据；成功结果重新读取当前批次投影，未知结果保留 UNKNOWN，不回放失权内容。

Filters 包含 accountState、maintenanceState、level、tagIds、registeredFrom/To、activityFrom/To、depositMin/Max、withdrawalMin/Max、currency、includeUnknown 和 keyword。accountState 复用权威 ACTIVE/DORMANT/UNKNOWN 活动分类；tagIds 使用既有自定义标签字符串。UTC 区间左闭右开，金额是精确 decimal 字符串并按实际财务币种处理。UNKNOWN、来源错误和真实零分开；不猜汇率或用近期分页代替累计。

## 逐客提交、恢复与取消

仅新增任务头和逐客项两表。任务头区分 JOB/ASSET；逐客项保存原 assignment、稳定 clientMessageId、发送路径、附件 ID、完整原 Conversation DTO、结果与尝试次数。DB 定时扫描以短事务恢复，无 token、伪 Authentication、租约或 MQ。

初次准备在 customer → JOB → recipient → actor/grant 锁序内持久保存完整 DTO，再进入实际发送事务。两阶段都先按稳定消息事实回查，再判断是否允许新发。发送事务复用原 HumanMessage/Conversation/Attachment/Maintenance 链，将消息、收件箱、逐客 SENT、维护执行一同提交，并在提交后广播。

任务 actor 使用真实活跃账号、专属坐席、当前归属及原 `service_m3_write` grant；作者、metadata 与审计均取持久 actor。HTTP ADMIN 消息拒绝 `bulk_` 保留前缀，不能借旧入口抢写任务的稳定消息 ID；客户自身消息命名空间保持原规则。

UNKNOWN 必须先查真实 metadata、实际作者、客户、发送路径与原 DTO 哈希。已发送后转绑仍恢复为 SENT，不重发；失权明细仍隐藏。缺 DTO 时先查稳定消息是否存在：无事实才恢复准备，有事实但缺载荷则保持待核实，不能假判失败或取消。重试复用原 DTO；准备后出现版本变化时明确冲突，不悄悄换版本或内容。

已确认需要人工核实的原消息事实保留 PENDING/UNKNOWN，自动扫描避开这些无进展项，防止堵住后续批次；显式恢复、重试或取消仍先核实原事实。维护周期未配置时 DUE 来源为 UNKNOWN，只有明确 includeUnknown 才纳入。

取消只锁 JOB → recipient，不反向取 customer。它先核实 UNKNOWN；已提交消息保留，确认未提交才取消。counts 始终等于冻结人数，PENDING+UNKNOWN 不算终态。

SERVICE 不记维护执行；MAINTENANCE 每条真实成功消息只记一次，后续新有效活动才完成维护周期。两类主动群发均排除停止维护者；客户主动求助后的普通单客 SERVICE 回复保持可用。replyTargets 为空，群发不清已有待回复游标。关闭、转接、归档或已转工单的冻结会话不能通过新建段绕过。

## 图片与迁移

一次上传经现有策略实际解码并重编码，私有字节位于 `private/support-bulk/`。每客独立 attachment 记录与权限；只共用底层字节。取消或过期一客引用不删除别客图片。ASSET 锁将素材过期清理与实际附件提交串行化；有已附着引用时保留历史对象。

素材到期时间只取 ASSET 的 expires_at。上传仅在数据库明确回滚时补偿删除新对象；提交结果 UNKNOWN 时保留字节，原 key 回查持久事实，避免删除可能已经提交的素材。

`scripts/migrations/20261001_support_enhancements_bulk.sql` 是可重复的增量迁移：创建两表，保留 ID/上传/命令去重，把原 object_key 唯一索引改为普通查找索引以支持共享字节，不修改旧客户、归属、消息或财务事实。必须先迁移再启用新服务；本轮仅允许独立验收库运行。

回退时先停止 bulk 扫描与相关素材清理，保留批次、逐客项、消息、维护执行与附件事实。已有同 key 多客引用后不能恢复 object_key 唯一约束，也不能删除已发图片或把发送结果回滚成未发。

## 验证入口与证据边界

`scripts/support-enhancements-check.ps1` 重跑 P1 共享链；`scripts/support-enhancements-bulk-check.ps1` 实跑 bulk 与旧消息/维护/附件回归，随后在另一个 Maven/Surefire JVM 中恢复前一进程的持久批次。边界固定 MySQL `cs_enhance_20261001`:33329、Redis 16341、私有存储 19041、HTTP 18141；凭据留在仓外。

所有 suite 要求本轮非零执行、零 skipped/failures/errors。场景与 XML 按时间、路径、哈希封存；每个 bulk AC 绑定实际执行的 testcase。`support-enhancements-runtime.mjs` 支持 core/bulk/integration，integration 重新运行全部上游检查，同一 run/snapshot 的 core 与 bulk 报告分目录保存，不能被后续套件覆盖。

仓外证据入口为 `C:/Users/jason/.codex/workflow-runs/customer-service-enhancements-20261001/backend-bulk/` 的计划、workflow-state、运行目录和独立审查。最终是否通过以当前快照的实测、独立审查和完成门为准，不以本文或编译通过代替。
