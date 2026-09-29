# S4 私有图片接点与验收边界

附件仅保存在私有对象存储，上传和发信分别持久幂等。本文是实现交接，不代表 S4 整体验收或生产上线。

## 文件与接点

- `SupportAttachmentService`：上传、受权读取、消息事务附着、取消、到期清理。
- `SupportAttachmentPolicy`：`nexion.support.attachments` 配置与只读能力响应；四项配置均无生产默认。
- `SupportAttachmentMapper`、`SupportAttachment`：独立持久表与安全视图。
- `SupportAttachmentController`：App 与后台登录接口；字节流不经过 CMS、不返回对象键或签名 URL。
- `scripts/migrations/20260929_support_attachment_s4.sql`：附件及上传/取消命令表，`CREATE TABLE IF NOT EXISTS`，不改历史消息或归属。

附件 ID 是 UUID 字符串。安全响应为 `{id,customerId,mime,bytes,width,height,state,expiresAt,messageId}`；`expiresAt` 是 UTC 暂存期限，ATTACHED 历史不因这个期限删除。内部 `objectKey` 使用与附件 ID 无关的双随机 `private/support/<uuid>/<uuid>`，不得向客户端序列化内部持久记录。

消息服务在既有客户锁事务内调用：

```java
SupportAttachment.View attachToMessage(
    Long customer, String actorType, Long actorId, Long expectedAssignmentId,
    String attachmentId, Long messageId);
```

`actorType` 只接受 `ADMIN` / `USER`，并核验当前登录主体类型与 ID。方法 `Propagation.MANDATORY`，只允许上传者附着本人当前客户 READY 且未过期附件；同消息重试例外，其他消息复用拒绝。附着前核验对象仍存在，存储故障使消息事务回滚。后台 expectedAssignmentId 必填且须与当前段、上传段相同；App 从认证主体取客户，允许未绑定留言。

`metadata(id, actorType, actorId)` 返回上述安全视图，也实时授权；消息 DTO 可只输出 attachmentId，再从以下内容端点获取图片。

## HTTP

后台根路径：`/api/admin/content/conversations/attachments`；App 根路径：`/api/app/support/attachments`。

| 动作 | 请求 | 结果 |
| --- | --- | --- |
| GET `/policy` | 登录态；后台 M3 read | available、allowedMimeTypes、maxBytes、maxPixels、ttlSeconds、unavailableReason |
| POST 根路径 | multipart `file`、`clientUploadId`；后台另传 `customerId`、`expectedAssignmentId`；`Idempotency-Key` | READY 安全视图 |
| GET `/{id}/content` | 登录态；后台 M3 read | PNG/JPEG 字节，`Cache-Control: no-store`、`nosniff`、`Vary: Authorization, Cookie` |
| DELETE `/{id}` | `Idempotency-Key`；后台 M3 write | 取消本人暂存；ATTACHED 拒绝 |

Range 为可选能力，本实现不切片，仍完整返回 200；每次 Range 请求也进入相同鉴权。客户端不得依赖 206。后台只允许当前有效顾问上传，主管授权本身不授上传资格。READY 仅上传者可读；转绑后旧顾问失去 READY 与历史访问，新顾问只读已附着历史。主管审阅 ATTACHED 仍须 M3 read；App ADMIN token 即使数值 ID 相同也不得当 USER 使用。

客户 ID/expectedAssignmentId 的 multipart 文本只接受十进制正整数字符，随后复用 `SupportBindingRequest.StrictId` JSON 解码校验安全整数；拒绝小数、指数、字符串转数截断和溢出。clientUploadId/command key 是 8–128 字符 `[A-Za-z0-9._:-]`，各自按认证主体持久隔离；上传 payload 摘要包含原始文件 SHA256、客户、身份、预期归属及 clientUploadId。相同 clientUploadId 可用新上传 key 恢复原附件，换载荷 409；上传 key 和消息 key 不共用缓存。

## 显式隔离测试配置

以下只是 runtime fixture 数值，不是生产默认：

```properties
nexion.support.attachments.allowed-mime-types=image/png,image/jpeg
nexion.support.attachments.max-bytes=1048576
nexion.support.attachments.max-pixels=1000000
nexion.support.attachments.ttl-seconds=300
```

任一项缺失、非正值或未知 MIME 使能力 available=false，上传 422。只声明 Java ImageIO 真正支持的 PNG/JPEG；WebP 明确拒绝。先按文件字节与像素检查，再真实解码并以新像素图重编码，舍弃 EXIF/comment/附加数据。伪 MIME、坏图、截断、SVG/脚本、路径/远程 URL 文件名拒绝，不抓取远程资源。

Spring Boot 配置也支持 `allowedMimeTypes/maxBytes/maxPixels/ttlSeconds` 驼峰属性。对应环境变量须使用 `NEXION_SUPPORT_ATTACHMENTS_ALLOWEDMIMETYPES`、`NEXION_SUPPORT_ATTACHMENTS_MAXBYTES`、`NEXION_SUPPORT_ATTACHMENTS_MAXPIXELS`、`NEXION_SUPPORT_ATTACHMENTS_TTLSECONDS`；属性词间连字符在环境变量中删除，不另插下划线。已用本仓实际依赖与已编译 Policy 在 JShell 运行 Spring Boot Binder 验证 dashed、camelCase、systemEnvironment 三种来源，全部 `BIND_PASS`；留有 `SupportAttachmentPolicyBindingTest` 两项回归，等待主线下一轮统一 Maven。

对象存储复用现有 `ObjectStorageService` 的 put/get/remove/exists，存储错误转为 503。上传回滚有删除补偿；补偿失败记录错误，不声称已经清除。进程在对象写入与数据库提交之间硬退出仍可能留下无元数据的孤儿对象，现有到期任务不能识别该类孤儿，应在运行维护层按存储清单与数据库核对后处理，不能把它当成已附着历史删除。

清理每 60 秒处理最多 100 项到期 READY，技术周期可通过 `nexion.support.attachments.cleanup-delay-ms` 修改；TTL 仍完全来自显式配置。清理、下载、附着都先锁稳定客户记录，再对附件 `FOR UPDATE` 当前读，防止 REPEATABLE READ 旧快照误删已发送历史。已配置附件即使后来关闭上传能力仍继续按原期限清理。清理失败保留 READY 供下一轮重试，过期内容授权已经拒绝。

部署环境必须确认共享 bucket 的匿名权限不覆盖 `private/support/`。随机对象键和应用鉴权不能替代 bucket 私有策略；本子任务未修改共享桶权限，主线真实存储验收必须匿名 GET 验证拒绝。

## 已跑检查与仍需集成证明

主线统一 Maven 运行产物 `target/surefire-reports/ffdd.opsconsole.content.application.SupportAttachmentServiceTest.txt`：9 tests / 0 failures / 0 errors / 0 skipped；`SupportAttachmentControllerTest.txt`：3 tests / 0 failures / 0 errors / 0 skipped。子任务回读两份报告确认。

已覆盖：PNG/JPEG 真解码重编码与 JPEG comment 清除、伪 MIME、脚本/SVG、截断、路径、字节/像素限额、ADMIN/USER 同 ID 混淆、READY 跨上传者/转绑访问、ATTACHED 当前读与跨客户拒绝、过期附着/已附取消/同消息重试、存储失败不附着、上传双键去重与换载荷冲突、安全整数与二进制/no-store。

单测以后最后一处修正移除了清理任务对当前上传能力开关的依赖，确保停用上传不阻止旧暂存到期清理；主线下一轮集成应包含该当前快照。

本子任务未执行 SQL、未启动服务、未操作真实 MinIO，也未 commit/push。真实 multipart HTTP、数据库重跑、持久幂等、存储故障/补偿、到期清理、匿名桶访问、Range 撤权、浏览器刷新和三端图片展示由主线 S4/S5/S6 集成继续证明。done-review 六维自检将这些列为未验证，不以单测或源码声明替代运行时结果。
