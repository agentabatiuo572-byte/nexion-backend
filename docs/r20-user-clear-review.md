**R20 USER_VIEW 清空：独立后端行为审查**

审查者：Noether。记录日期：2026-10-27。工作区：`D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend`。基线 HEAD：`090a7e5032a8f6e781150201ce51005d554890de`；本次验收对象是该基线上的冻结实现，不能把基线 SHA 当成包含本次改动的提交。

**首轮后端局部结论：本地后端源码及隔离 MySQL 服务层范围通过；两项已报告 P2 在下述范围关闭，当时未发现新增明确 P0/P1/P2 阻塞。** 不继承 R19 分数，不以平均分替代逐项门槛。本结论不是正式 HTTP/JWT、WS、TEST 部署、APP 恢复链或物理设备的验收。

**最终增量状态：R20-REC-P2-01/02 在本地行为范围关闭；已受理父线程 UGGOR9u8 稳定通知、活页面 clear/重复水位及三条基本恢复链的限定实景，当前未发现新增明确 P0/P1/P2 阻塞。最终七项分数与未覆盖边界见文末冻结节。旧低分、问题快照及失败日志保留；旧 DRX3akFz 裁偏截图只作历史，不作最终视觉依据。原后端 P2-A/B 关闭依据继续有效。本报告已完成并冻结，不代表正式 Java/JWT/WS 或部署验收。**

本轮恢复后仅写此报告，未修改生产或测试，未重新运行 Maven、操作浏览器、提交、推送或部署。独立测试由本审查者编写，生产实现由 Jason 负责；两个批次均由主线程执行。本审查者直接读取了本机日志、Surefire XML、实际测试断言和生产差异，并重新核对冻结清单中全部 19 个文件，19/19 相符。Jason 尚在补 APP 的 peer-change / clear-conflict 409 恢复，**本报告不预签该链路**。

**逐项限定评分（各项独立，满分 10）**

| 审查项 | 分数 | 已验证依据 | 本项签署边界 |
| --- | ---: | --- | --- |
| USER 身份及 account / peer / member / version 授权边界 | **9.1** | 控制器从 USER principal 派生账号；独立实 DB T4 验证错账号、错 peer、漏成员、旧版本均不产生部分清空；既有控制器/写门测试通过 | 包括源码、合成 principal 的 MockMvc 和服务层；不含真实 JWT 签发、网关与登录 |
| account+peer / CID 持久化 USER_VIEW 水位 | **9.3** | T1 新 DataSource、SqlSessionFactory、服务代理重建后仍不可见；T2 覆盖零水位；T11 覆盖归档成员及旧 CID 归属变化 | 证明数据库持久化，不把重建 Java 服务图等同重新登录、换机或数据库进程重启 |
| APP 列表、计数、分页、详情、预览与未读过滤 | **9.3** | T1/T2/T3/T11：过滤先于 LIMIT；旧 cursor 不回流；旧预览、未读及 nextCursor 清空；新消息仍可见且可读 | 覆盖实际 mapper / 服务返回；前端缓存合并和展示不在本次实 DB 范围 |
| 原命令重放、读回及清空后的新消息 | **9.3** | T3：原 clear key 保持原水位；原 send key 和 command readback 按当前可见性重投影；新 incoming 保留、无重复写入；T10 保留已成功原 key 恢复 | 服务层幂等与读回通过；APP 对新的 409 分支如何重试/重进待后续独立审查 |
| 附件与 markRead 对并发清空的隔离 | **9.2** | T6/T7/T8 使用实际双连接、事务、mapper 锁和迟到读取；旧附件内容/元数据被拒绝，旧 receipt 不写入 | P2-A 关闭；对象存储字节用 mock，附件 upload 重放竞态未由这 11 项单独实测 |
| 稳定客服身份及换客服后新写入隔离 | **9.2** | T9/T10 同名不同 ID 不混入旧 CID；新写入返回 `CONVERSATION_PEER_CHANGED`，合法当前 peer 仍能发送；旧成功命令不被误挡 | P2-B 关闭；持久 assignment 变更在夹具中完成，不声称完整转客服 HTTP 流程已跑 |
| 客服原记录保留、必需审计及失败原子性 | **9.3** | T1/T4/T5 对消息、human metadata、附件元数据、receipt 原行比对；staff 查询仍含内部记录；强制 audit INSERT 失败时两类水位回滚，幂等记录不成为成功 | 实际 SQL 数据与服务权限路径；真实存储对象完整性、全量运营数据库未验 |
| 增量迁移及启动注册 | **9.1** | 独立实 DB 每例安装同一新增迁移两次；启动注册与历史 SQL 差异已审；18 项迁移契约通过 | 新隔离 schema 上的重复安装通过；TEST 发布、备份恢复、既有部署升级未执行 |

**两项 P2 的关闭依据**

**P2-A：取得客户锁之前的普通读取，可能让后续读取沿用旧 RR 快照。** 原审查路径是附件先读 customer 再等待客户锁，以及 markRead 先取得会话归属再等锁；若清空先完成提交，后续普通 SELECT 仍可能看见旧水位。冻结实现为 `SupportAttachmentService` 的 upload / metadata / content，以及 `AppSupportService.markConversationRead` 明确使用 READ_COMMITTED，保留原客户互斥锁和鉴权。

独立 T6/T7/T8 的时序不是普通顺序用例：清空事务已写水位并持有锁但未提交；另一个连接完成真实生产非锁定读取，并到达真实 `SupportBindingMapper.lockCustomer`；断言读取任务此时未完成，再提交清空，让读取继续。附件 content 返回业务 404 且未调用对象存储 get；metadata 返回 404 且不访问对象存储；markRead 返回 404、data 为空、receipt 表仍无旧回执，随后详情也为空。工作线程显式安装 USER Authentication，因此实际走到锁与水位检查，而不是用 401 提前退出冒充通过。

结论：**P2-A 在附件 content / metadata 与 markRead 的已测服务入口关闭。** upload 的事务修正经源码及既有附件测试核对，但未借这三项用例声称 upload 重放并发已实测。未执行“撤回生产修复”的旧版本实 DB 红绿对照；闭环依据是已识别的旧源码路径、最终修正和上述真实并发断言。

**P2-B：当前客服变化后，新消息仍可能写入旧客服 CID。** APP 的展示身份采用持久 `owner_agent_id`，新写入若只验证当前分配关系，会把新客服消息写到旧客服历史下。冻结实现新增 repository `requirePeerForWrite`，以 `FOR SHARE` 读取 CID 持久 peer，APP / staff 新写入均在提交新消息之前核对，不符返回 409 `CONVERSATION_PEER_CHANGED`。已提交原命令的恢复位于新写入检查之前；创建/复用仍按账号与稳定客服 ID，并使用客户互斥锁。

T9 在 A/B 显示名相同、持久 assignment 已切 B 时，验证 B 对 A 的旧 CID 新发送被拒绝，原消息与 client metadata 未变；B 自己 CID 的发送正常，A/B 详情不串。T10 验证 APP 新 key 向旧 CID 发送被拒绝，但原已成功 key 仍恢复且消息不重复，向当前 B 的合法发送成功。T11 额外证明历史 CID 被遗留归属改写后，已清空的旧消息和附件仍受 member 水位约束，新消息可见。

结论：**P2-B 的后端新写入隔离与原 key 恢复关闭。** 测试通过持久 assignment 变更构造转接完成后的状态，没有调用完整转客服端点；APP 收到 409 后的可恢复交互没有在此签署。同客服并发首次创建仅有既有测试与源码依据，未包装成本次新增双连接用例。

**两个批次及初轮失败历史（保留，不重标）**

| 批次 | 实际结果 | 统计口径 |
| --- | --- | --- |
| [初轮 final-matrix.log](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/final-matrix.log) | 210 run；4 failures、1 error、0 skipped；BUILD FAILURE；总 02:00；2026-10-27 15:19:53 +09:00 完成 | 另 10 类 199 项绿；本独立类 11 项中 6 通过、4 失败、1 error |
| [二轮 mysql-review-r2.log](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/mysql-review-r2.log) | **11/11；0 failures / errors / skips；BUILD SUCCESS**；测试 22.21s，Maven 总 01:05；2026-10-27 15:25:56 +09:00 完成 | 仅重跑修正后的独立 MySQL 类；生产文件未变 |

当前成立的是 **199 个非实 DB 定向项 + 11 个实 DB 项在两个批次通过**，共 210 个不同用例；不是第二轮整套 210 重跑，也不是仓库完整 suite 通过。日志中的 1365 是测试源编译文件数，不是本次执行的测试数。

初轮四个 failure 均来自本审查者的 Authentication 夹具遗漏：

- `persistedClearSurvivesNewServiceGraphAndPreservesStaffOriginals`、`archivedSiblingIsCoveredAndMemberWatermarkSurvivesLegacyReassignment` 的附件断言期望 404，实际 401。
- `attachmentContentStartedBeforeClearMustRecheckAfterCustomerLock`、`attachmentMetadataStartedBeforeClearMustRecheckAfterCustomerLock` 未到客户锁 latch。生产 `actor()` 因线程缺 USER SecurityContext 在任何相关 SQL 前返回 LOGIN_REQUIRED。
- 修正仅在独立测试当前线程及 executor 工作线程提供 USER Authentication，并在结束时清理。原来的 404、无字节读取、锁时序及不回流断言保持；没有修改生产鉴权或改断言接受 401。

初轮一个 error 位于 `originalClearAndSendReplaysKeepOldBoundaryWithoutHidingNewMessage` 的测试 DTO 转换：独立 Graph 使用裸 `ObjectMapper`，把响应计算属性 `linkTarget` 视为未知字段。实际 command readback 已完成，失败在测试 `convertValue`；生产 Spring web mapper 默认配置与该裸 mapper 不一致。夹具改用 `Jackson2ObjectMapperBuilder.json()` 并施加生产 `DateTimeFormatConfig` customizer，显式断言 unknown-property 配置为 false。未修改生产 DTO、全局 mapper 或幂等逻辑。

因此初轮红项定性为 **fixture 缺陷**。不把它们宣称为两项生产 P2 的旧版实 DB 复现，也不隐去初轮 BUILD FAILURE。更正后的原断言全数通过。

初轮其余 10 类的实际结果如下，均 0 failures / errors / skips：

| 测试类 | 通过数 |
| --- | ---: |
| AppConversationUserClearTest | 11 |
| AppSupportCommandRecoveryTest | 4 |
| AppSupportServiceTest | 39 |
| OpsConversationServiceTest | 56 |
| SupportAttachmentServiceTest | 14 |
| CustomerConversationReuseTest | 5 |
| MybatisConversationRepositoryTest | 16 |
| ConversationSocketHandlerTest | 9 |
| StartupSchemaMigrationContractTest | 18 |
| UserBusinessWriteGateFilterTest | 27 |
| 合计 | **199** |

**独立实 DB 文件与逐例覆盖**

文件：[R20UserViewClearMySqlReviewTest.java](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/test/java/ffdd/opsconsole/content/application/R20UserViewClearMySqlReviewTest.java)。完整类名：`ffdd.opsconsole.content.application.R20UserViewClearMySqlReviewTest`。直接读取的 [Surefire XML](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/target/surefire-reports/TEST-ffdd.opsconsole.content.application.R20UserViewClearMySqlReviewTest.xml) 记载 tests=11、failures=0、errors=0、skipped=0、time=22.209。

| 编号 | 实际用例名 | 关键覆盖；全部通过 |
| --- | --- | --- |
| T1 | persistedClearSurvivesNewServiceGraphAndPreservesStaffOriginals | 重建服务图后水位仍在；他账号/他 peer 不受影响；用户旧附件拒绝，staff 历史保留 |
| T2 | filtersBeforeCursorLimitAndOldHistoryCannotReenterIncludingZeroBoundary | LIMIT 前过滤；列表 total；旧消息 cursor；空会话真实零水位 |
| T3 | originalClearAndSendReplaysKeepOldBoundaryWithoutHidingNewMessage | 过期 processing lease 后仍保留成功 clear 回执；旧 send POST/读回只返回当前可见消息；同 CID 新消息未读 1→0 |
| T4 | wrongAccountPeerMemberOrVersionCannotPartiallyClear | 错账号/peer、漏成员、旧版本；无部分水位或审计成功 |
| T5 | requiredAuditFailureRollsBackBothMarkersAndDoesNotCommitSuccessfulIdempotency | 真实 SQL trigger 使审计失败；水位整体回滚，幂等状态 FAILED |
| T6 | attachmentContentStartedBeforeClearMustRecheckAfterCustomerLock | 实际并发清空后旧附件内容拒绝，不取存储字节 |
| T7 | attachmentMetadataStartedBeforeClearMustRecheckAfterCustomerLock | 实际并发清空后旧附件元数据拒绝 |
| T8 | markReadStartedBeforeClearMustNotReturnOldTranscriptOrWriteOldReceipts | 实际并发清空后旧 read target 拒绝，无旧回执/内容 |
| T9 | changedStaffCannotWriteNewMessagesIntoPreviousStaffCidButCurrentPeerStillWorks | staff 换 peer 后旧 CID 新写入 409，当前 peer 正常 |
| T10 | changedPeerRejectsNewAppReplyButRetainedOriginalReplyStillRecoversWithoutDuplicate | APP 新写入 409；旧成功 key 恢复不重写；当前 peer 正常 |
| T11 | archivedSiblingIsCoveredAndMemberWatermarkSurvivesLegacyReassignment | 归档 sibling 覆盖；CID 归属变化不复活旧历史/附件；新消息正常 |

测试使用父线程独占的 MySQL 8.4.6，loopback `127.0.0.1:33327`、schema `r20_user_clear`，固定 datadir 为 `D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/mysql-data`。测试每次取连接均校验 schema、端口、datadir、当前数据库账号；使用 advisory lease 并仅重置批准的夹具表。URL 采用经批准的完整连接参数，不读取或输出本地凭据文件。`R20_MYSQL_IT=true` 时缺配置会失败；本次不是 skip。

实际使用 MyBatis mapper、Spring 事务代理、生产 ownership / assignment / 幂等 / 审计路径；连接默认 RR，方法级事务隔离按生产注解执行。未启动完整 Boot HTTP 服务；无后台调度器。附件对象存储使用固定字节 mock，无真实上传下载。新服务图测试不保留旧 SqlSession 缓存，但没有重启数据库。并发证据来自两个真实事务与 mapper latch，未额外采集 performance_schema 锁等待时长。

**读取路径及合同结论**

- 控制器 GET `/api/app/support/conversation-management` 与 DELETE `/api/app/support/conversations/{cid}` 使用 USER 主体、`Cache-Control: no-store`；请求只允许 USER_VIEW，peer / CID / expectedVersions 在所属边界校验。客户端不能通过请求体选择别人的账号。
- clear 在客户锁内校验成员和版本，peer 与 member 水位单调前进，必需审计同事务；新增仍可见的未确认成员导致冲突。归档成员也计入覆盖范围。不存在物理删除客服消息/附件/receipt 的实现。
- SQL 按 account、稳定 staff ID / 未分配 CID 计算可见性，取 peer 与 CID 已存水位的较大值；有 marker 且值为零与无 marker 分开。列表过滤在分页前执行，详情、公开消息、未读与附件均使用相应用户侧约束；staff 原始查询不套用户隐藏规则。
- 原 clear 命令回执是该命令当时的边界，**合法地低于后来新消息 ID**。同 key 重放/读回不应重新提高水位，也不应套用“新发起 clear 必须覆盖当前成员消息”的条件。T3 已验证新消息保留、旧 send 读回不泄露被清空内容；这属于后端既有命令语义，不等于 APP 新 409 恢复已验收。
- WS 清空监听为 AFTER_COMMIT，按 USER/account 定向失效通知并重新核验连接授权；相关 mock 测试通过。独立实 DB Graph 的事件发布器不连接真实 WS，故只能签署这部分源码/单测事实，不签署网络投递。
- 新迁移只新增两张用户清空水位表，并注册到启动安装器。历史 migration 与基线 schema 未被改写。完整部署必须先安装该迁移；直接运行应用不等于已执行迁移。本轮没有运行 TEST 部署。

**未覆盖及后续交接，不作为已通过项**

1. 正式 HTTP/JWT：真实令牌签发、完整过滤链、fresh authenticated client、重新登录与换机实际读回。数据库水位已持久化，但这些端到端动作尚未执行。
2. 正式 WS：真实多会话投递、失效通知到达、撤销令牌/重连、部署下的读写一致性。当前只有源码及 mock 连接测试；无真实 WS 服务签核。
3. TEST 发布：部署迁移、真实环境权限/配置、进程及数据库重启、回滚/恢复与升级；本次不含任何推送或部署。
4. 真实对象存储上传、下载及附件 upload 重放竞态；当前覆盖服务层 USER/ADMIN 门及 SQL 隐藏规则，不等同真实存储链路验收。
5. APP 的 `CONVERSATION_PEER_CHANGED`、clear version/member 冲突的刷新/重新确认/原命令恢复，仍由主线程及第二审查者后续复核；不继承 R19 或本报告分数。也不包含物理设备、视觉、用户 5237 会话。

这些是明确的验证范围差异，不将其包装成已复现产品缺陷，也不借局部绿色宣称全部正式业务已验收。

**证据与冻结指纹**

以下均为本机直接读取所得 SHA-256；日志未改写，首轮失败证据保留。

| 证据 | SHA-256 |
| --- | --- |
| final-matrix.log | `fa1309397faa3499cc0656c2878fa7de595536b0cb3d69ad04807a050177b150` |
| mysql-review-r2.log | `f8f5a013b64fab66aa0033fe96a742c93e247f88c111451ae4e98d79473a28ae` |
| 最终独立测试源 | `4679955d412849fe643b611a65afa613066dfa84caa167880d399bf5d28f52b7` |
| 最终 Surefire XML | `65e7f4f407ea289d175e477acfa476592bdbda7d27853a340b38eb0cd767520c` |
| [target/r20-production-sha256.txt](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/target/r20-production-sha256.txt) 文件本身 | `72c0cf161de137923400ca6745f4711cb5be3896e72f261e640c739fcde25b00` |

冻结生产清单抄录如下，避免只依赖可清理的 target 文件；恢复写报告时已逐文件核对，无漂移：

```text
scripts/apply_startup_schema_migrations.ps1 39ebd977d84192f1ceb99141b65f54600ac63dc8a8bbbbf6744ff532fb11ea7d
scripts/migrations/20261027_app_conversation_user_clear.sql 51f2cec170f08e79bd38f39a879c43375c9a2fb5e53681a20b9289638a1994ef
src/main/java/ffdd/opsconsole/content/application/AppConversationManagementService.java 63909f8fe785f4df57096a66ca2e6d3d88020d2e3c515c53306bbba4747621ad
src/main/java/ffdd/opsconsole/content/application/AppConversationVisibilityService.java d0268b58238bcdf04c969d8df5cd52f61459c5019b8cc4efc368e04add51ac12
src/main/java/ffdd/opsconsole/content/application/AppSupportService.java ec47cf7c2b69c3535abc09af4d9e42c992ee1ce7170bfb1b150fd4717fd830fa
src/main/java/ffdd/opsconsole/content/application/OpsConversationService.java 71fabfdfad073bcc0106ed445d4283c60d396963eb23da140b4889817d465617
src/main/java/ffdd/opsconsole/content/application/SupportAttachmentService.java 67a20102cf0c6b10547ef6d813b5b4639288ad7b3ca87eb50129d7c5bcb22b5c
src/main/java/ffdd/opsconsole/content/domain/ContentConversationView.java 747846352c658b1c066d3feb324576c3cdef9797834816b8606d0b822b90deb0
src/main/java/ffdd/opsconsole/content/domain/ConversationRepository.java b348eeca8d3de651a8df05a5ca71467ff0edc5d09fe39002aa4a503572a58028
src/main/java/ffdd/opsconsole/content/domain/ConversationUserClear.java 0334edc674f233ff156b7e1e328e80b5dcb3f6d711bff36097829dc1e1efe1b3
src/main/java/ffdd/opsconsole/content/infrastructure/MybatisConversationRepository.java d86192f075934de52099cef9899acca0db0a9efd9890ceef1d0ba248e97a9d82
src/main/java/ffdd/opsconsole/content/mapper/AppConversationClearMapper.java 17b327e69e88bfff84a11a952cdaef2f217dc6e7bed45111bbca60ceacba7a32
src/main/java/ffdd/opsconsole/content/mapper/AppConversationVisibilitySql.java 0c3a22dcc0b7f79f65af18f6b8fe639750566c4f280c7745c739ce9788196cdc
src/main/java/ffdd/opsconsole/content/mapper/ConversationMapper.java efb567e1c765e9b5875d357b36aacd0076653a42d2b105f2615e1c54997fb28d
src/main/java/ffdd/opsconsole/content/mapper/ConversationMessageMapper.java 602968230cdd52978e58e6631e409344694f3fdf816ea5c18d345d53eb0dcf5e
src/main/java/ffdd/opsconsole/content/mapper/SupportAttachmentMapper.java b56137d4f149d497a441095fc91a2202ad1774ef14d15c9e8c50a733d0499073
src/main/java/ffdd/opsconsole/content/realtime/ConversationSocketHandler.java 57f606090d0ab7fbbe65f3d432089a2d555cbb81839f9fff60b5d58f2fdbc19e
src/main/java/ffdd/opsconsole/content/web/AppConversationManagementController.java 1bef6440226fd25de8d34375424fd60616018d3508ade954fe3cbb008cf0f0a0
src/main/java/ffdd/opsconsole/shared/security/UserBusinessWriteGateFilter.java 0b4f7771e313a8869c6293a75720116dd7c1c85a1f9a2f5129b6348baa2033e2
```

**2026-10-27 增量复审：APP peer409 / clear409 与 backend command readback**

本节以 APP 基线 `19f1a42b6b0dbda7ba6579aae236158f5adf7b93` 和 backend 基线 `090a7e5032a8f6e781150201ce51005d554890de` 的当前冻结工作树为对象。两者仍有未提交改动，基线不是交付提交。只读审查生产差异、调用上下文、现有测试源码及日志；未执行测试、构建、浏览器操作，未修改实现或测试。收益卡及既有视觉范围未重审。

**本节结论：P0=0、P1=0、P2=2。** 精确终态分类、后端 clear 拒绝读回和主动 restart 的局部语义可接受；页面在分配变更、清空成功时的本地状态同步仍有具体缺陷。下面两项是沿实际响应式调用路径确认的源码问题及可交给实现者执行的最小用例，**本轮没有运行这些新增用例，也没有浏览器复现记录**。不得把现有 390 项绿色解释为已覆盖这两个时序。

| 增量审查项 | /10 | 当前结论 |
| --- | ---: | --- |
| peer409 / clear409 精确终态识别 | **9.3** | 仅 ApiError 的 http/business、精确 409 和明确原因命中；普通 409、网络/协议错误、相似字符串不被视为安全终态 |
| backend 原 clear 拒绝的命令读回 | **9.3** | 限本账号 clear scope、SUCCEEDED 留存响应、code=409 及两种原因；PROCESSING/FAILED/UNKNOWN 及其他 scope 保持未确认语义 |
| original key 与未知正文跨分配变更保留 | **8.4** | store 保留 key 正确；页面已稳定 unknown 会被清空，后续输入可覆盖对应恢复正文，见 R20-REC-P2-01 |
| 旧客服只读与被拒绝正文保留 | **8.6** | peerChangedIds 不改原 owner/status，关闭旧窗口写入且不自动跳转；但后到的分配通知可清掉已稳定的失败正文，同 P2-01 |
| 用户主动 restart 及目标隔离 | **9.1** | 仅导航到 start=advisor，不自动发送，不重归属旧 CID；新提交才走当前 advisor/可用会话选择，旧 CID composer 未被主动迁入新窗口 |
| clear409 拒绝、刷新与再次确认 | **9.2** | 精确拒绝只释放该命令；不调用 applyUserClear、不自动再次 DELETE；刷新成员/版本，下一次明确确认使用新 key；未知结果仍保留原 key |
| clear 成功后的页面数据/草稿边界 | **8.4** | store 水位与 composer 删除已实现，存活页面未同步清除旧 draft/failed/imageDraft，见 R20-REC-P2-02 |

**R20-REC-P2-01：已稳定的草稿/unknown 会被同账号分配通知清空。**

位置：[chat.vue:281](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:281)、[chat.vue:558](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:558)、[chat.vue:563](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:563)；通知入口：[conversations.ts:303](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:303)。

通知现在正确地只重启 realtime、增加 scopeInvalidated，不再删除账号 outbox。但页面仍把 accountBindingEpoch 和 scopeInvalidated 放在同一个同步 watcher 中，无条件把 imageDraft、failedHumanSend、draftText 清空。恢复 watcher 只观察当前 saved composer 与 humanSendBusy；一个已稳定的分配通知不会改变这两项，故不会自行恢复。humanComposerKey 仍为原 `conversation:<cid>`，其 watcher 也不会因 scopeInvalidated 增加而运行。

最小用例：

1. 使用现有 `mountRealHumanChat()`，打开旧 CID；发送得到普通 409 / 网络不确定结果，使 failedHumanSend 为 unknown，原 key 已保存。
2. 输入下一段草稿，**先 await Vue.nextTick() 让所有 saved-composer watcher 完成，再触发通知**；可再等待一 tick，确保不是发送 finally 仍在排队。
3. 令同账号 `scopeInvalidated++`，保持 commandResult 未确认、历史中没有精确 echo，再 await Vue.nextTick()。
4. 应保留 unknown 正文、下一段草稿与发送阻塞；源码实际会令页面 failedHumanSend=null、draftText=""。store 的原 key/正文暂仍存在，但页面阻塞由本地 failedHumanSend 控制，接着输入会通过同步保存 watcher 用 failedSend=null 覆盖旧恢复正文。

已有 `messages-start-entry.test.ts` 的 `preserves unknown and its draft when the same account assignment scope changes` 在设置 draftText 后立即增加 scopeInvalidated，再一起 nextTick；前一次草稿写入排队的恢复 watcher 会掩盖缺陷。该用例需要在两步之间先完成响应式 flush。实时 store 测试只断言 store/存储，不能替代这一页面状态断言。精确 peer409 已返回、failed 正文已稳定后再收到通知也会受同一清空逻辑影响。

最小修复方向：区分真正账号切换和同账号分配变更。后者可使私有图片读取/上传策略等异步工作失效，但不能无条件清掉正文、failed/unknown 及原 key；若需要重建页面状态，应在该通知处理本身明确从同 owner 的 saved composer 恢复，不能依赖另一个恰好排队的 watcher。补“已 flush 的草稿”“已 flush 的 unknown”“已 flush 的精确拒绝”三种短用例即可，无需扩大全矩阵。

**R20-REC-P2-02：clear 删除 store composer 后，存活页面仍显示并可写回旧本地状态。**

位置：[conversations.ts:155](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:155)、[chat.vue:563](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:563)、[chat.vue:1043](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:1043)。

applyUserClear 会按水位删除该 peer/CID 的旧 composer，但页面 saved watcher 遇到 `!saved` 直接返回，没有清掉 draftText、imageDraft、failedHumanSend。scopeInvalidated、humanComposerKey 不因用户水位前进而变化，因此上面的账号/分配 watcher 也不会代为处理。threadMessages 即使已没有服务端会话，仍会从旧 failedHumanSend 生成本地气泡。

最小用例：保持一个真实 CID 页实例存活，写入草稿及 failed/unknown 状态并等待 flush；让另一端清空的权威 marker 经 conversationManagement / 详情 / 历史补页被该 store 接收。为避免争论空会话展示，可同时保留一条高于水位的新消息。store 应只剩新消息、旧 composer 被删除；当前页面的旧失败气泡与草稿却不清除。继续改动一字会调用 saveComposer，把旧本地内容重新放回 store，并带上当前新水位，从而不再被同一 marker 清理。

现有 store 用例断言 `store.composer(...).text === ''`；现有跨设备历史用例断言旧服务端消息/附件不在 threadMessages，但未先建立页面 draft/failed/imageDraft，因此未覆盖上述状态分叉。这不否定后端水位与 mapper 过滤，也不把“clear409 被拒绝应保留草稿”误写成“所有 clear 都清草稿”。

最小修复方向：把当前 owner/CID 上**已确认水位前进所删除的旧 composer**同步到存活页面，重置相应旧 draft/failed/imageDraft/本地气泡；不要仅以 saved 缺失作无条件全局清空，以免影响首次加载、账号恢复或水位之后的新草稿。保留原 key 的未知结果读回责任，不应通过删除全部 pending key 解决显示残留。只需补“当前页收到新 marker”和“该 marker 之后新草稿不被重复 marker 清掉”的局部回归。

**已接受的增量与证据限定**

- `isConversationPeerChanged` 只识别精确 peer409；`isConversationUserClearRejected` 只识别 `CONVERSATION_USER_CLEAR_CONFLICT` / `CONVERSATION_USER_CLEAR_MEMBERS_CHANGED`。对普通发送，clear 原因不被泛化为确定发送失败。command 仅对 userClear 释放相应 fingerprint；读回失败与 null 不冒充成功。
- 已核对真实错误包装：backend 的 `ApiResultHttpStatusAdvice` 会把非零业务 code 映射为 HTTP 状态，APP `api-client.ts` 保留该 status / code；WS adapter 也把 ACK code 写入 status。未仅凭测试手造 ApiError 推断传输契约，也未将“HTTP 200 包着 code409”的假设当成当前正式后端缺陷。
- 后端 `recoverSucceededCommand` 新分支位于 SUCCEEDED 处理内，进一步校验精确账号 scope、响应 code 和 reason。它表达“命令执行器已留存确定拒绝”，不是把 FAILED / UNKNOWN 状态变成拒绝。错 scope、错账号、非 409、普通 hash conflict 均不命中。用户清空成功回执仍返回原边界，不隐藏后来新消息。
- `sendUser` 的 peer409 标记只在当前 account epoch 下更新；晚到旧账号结果不能污染新账号。旧 CID header 继续使用原 owner，而非新 advisor 名字。retryHumanSend 对明确 peer409 的 retryable=false 拒绝重发。主动 onRestart 仅发出导航，后续 start 路径自己读取当前 advisor；没有偷偷复制被拒绝正文或自动 POST。
- clear 冲突收到后刷新列表而不再写入，composable 的下一次 remove 重新弹确认。原 key 不确定时仍由 pending 存储与 commandResult 恢复，不用普通 409 或 `SUPPORT_COMMAND_NOT_COMMITTED` 擦掉 key。拒绝情况下草稿/记录保留的 store 测试与实现相符；成功清空的页面镜像问题单列 P2-02。

实际读取的 backend 日志：[r20-clear-recovery-red.log](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/target/r20-clear-recovery-red.log) 为 6 项、1 failure，预期精确 `CONVERSATION_USER_CLEAR_CONFLICT`，原实现返回 `SUPPORT_COMMAND_NOT_COMMITTED`；[r20-clear-recovery-green.log](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/target/r20-clear-recovery-green.log) 为 AppSupportCommandRecoveryTest **6/6** 加 AppConversationUserClearTest **11/11**，共 **17、0 failure/error/skip**，2026-10-27 16:47:51 +09:00 完成，Maven 总 01:04。新增两项命令读回测试分别覆盖确切拒绝和非终态/错误 scope 不能冒充拒绝；这批不是新的 MySQL 并发复验。

APP **8 文件 390 passed** 是主线程在本次委托提供的执行结果；本轮未取得能对应这一计数的原始 APP 日志文件，故不写成审查者独立实跑/逐条日志核实。已读取 policy、realtime、threads、messages-start-entry、nova-queue-page 的本次测试差异，以及既有删除 composable、API/水位和 SFC harness。页面 peer409 用例执行真实 chat script/store，但 `.vue` 子组件在该 harness 内被替代，不代表真实页面 DOM、键盘或浏览器交互已验。

首轮 199+11 两批结果仍按原快照保留。本次重新比对原 19 文件 manifest，仅 AppSupportService 变化，其余 18 个相符；不能宣称首轮 11 个实 DB 重新覆盖了新增读回分支。旧后端 P2-A/B 所涉隔离与 peer 写入校验未改，关闭结论延续；当前两个页面 P2 使用新编号，不混为旧 P2 重开。

**本节读取的冻结指纹（SHA-256）**

| 文件 | SHA-256 |
| --- | --- |
| APP src/lib/conversation-delivery-policy.ts | `4e85ec9e38a0b72774470dd62089d9fe3c4634b90f56c4872ed6faaf72e4c802` |
| APP src/store/conversations.ts | `85ae46e25fd3b67d681d1991003bd308b6a14d5042b1c306ad2fc1dab1f44760` |
| APP src/pages/support/chat.vue | `43fc423c7a49c966345662b00f17e5d41df6c3f7e41ec22b00ae8abc63399b93` |
| APP src/api/conversation-management.ts | `989db7e0b5aa32cd67b465cad960861b47423d60e1cb28f5eeaf7dcf66b107df` |
| APP src/composables/use-conversation-delete.ts | `24a2bf466c35582512d950c1d566c93ef9cac0ed5659ecae2acd87de9ea864f2` |
| APP src/pages/support/messages-start-entry.test.ts | `3cb5651587b657c1458ef7c3a17eecd8bb9a7a300f6b8f0b395eebf00f972692` |
| APP src/store/conversations-threads.test.ts | `b081eae7d38f7336c7e285ebe4bfcaf25b29b9dd1f70df311576953802e3b599` |
| APP src/store/conversations-realtime.test.ts | `545662a9613f6a3fe99c71801e9e5b562ba4958992904db976c94f571b525f49` |
| backend AppSupportService.java | `988c88700587752bfc41dfebdbba4d024e4ddfbf3cbfbec5d8e449f43250198f` |
| backend target/r20-clear-recovery-green.log | `064fba97b05612004bd6990c986a5047cd5b66e53a7bc6e8fe7c3779a18151e8` |

截至上述源码复审，真实登录、JWT/WS 端到端、TEST 部署、物理设备与本轮 409 实景交互尚未纳入证据，不预签。后续收到的父线程限定实景记录见下节；页面两项 P2 的修复及最小复验仍由主线程安排。

**父线程 DRX3akFz 实景补证受理（2026-10-27）**

执行者是父线程，环境为 APP 构建 `index-DRX3akFz.js`、本机 HTTP 代理 `5244→5240` 注入的合成 fixture，**不是正式 Java 服务**。本审查者只读取下列三个脱敏 JSON，并实际用 view_image 查看五张已有 PNG；没有操作浏览器、发送请求或重演交互，不称为本人实景。

| 父线程场景 | 本次直接读到的证据 | 能支持的限定结论 |
| --- | --- | --- |
| 版本拒绝后保留，显式重确认 | [live-clear-conflict.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-clear-conflict.json) 的两次 DELETE key 不同，retainedAfterRefusal / emptyAfterExplicitReconfirmation / emptyAfterReload 均为 true；[保留截图](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-clear-conflict-retained.png)、[重确认后截图](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-clear-after-reconfirm.png) 可见行保留→空列表 | 支持 fixture 下拒绝不删记录、用户重确认用新 key、成功后刷新仍空 |
| 首响应丢失，读回终态拒绝 | [live-clear-lost-refusal.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-clear-lost-refusal.json) 记录 DELETE→GET commands→第二次 DELETE；直接计算 GET 路径中 key 的 SHA-256 前 16 位，与第一 DELETE 的脱敏 key 相同；第二 DELETE 使用不同 key；[拒绝后截图](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-clear-lost-refusal-retained.png) 仍有会话，finalText 为服务空列表 | 支持原 key 读回、拒绝后记录保留及后续新 key 清空；503 与权威 409 的具体响应状态来自父线程取证说明，脱敏 JSON 本身未保存响应码/响应体 |
| peer409 后只读，用户主动 restart | [live-peer-changed.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-peer-changed.json) 的 before 包含旧姓名、转接说明与原发送正文，after 为当前客服和空输入入口；[旧窗口截图](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-peer-changed-readonly.png)、[当前客服截图](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-peer-current-advisor.png) 可见对应身份/正文变化 | 支持明确拒绝后的初始只读状态和主动转入当前客服。记录中有原 read POST 和被拒绝的 reply POST，未见 restart 自动产生 create 或额外 reply；不表述成整个流程没有 POST |

截图仅用作可见内容佐证，未据此新增尺寸、完整布局或视觉评分。拒绝注入及用户点击顺序按父线程执行记录归属；读取 JSON/截图不等于本审查者重新操作。

本次读取时 chat.vue 与 conversations.ts 仍分别为 `43fc423c7a49c966345662b00f17e5d41df6c3f7e41ec22b00ae8abc63399b93`、`85ae46e25fd3b67d681d1991003bd308b6a14d5042b1c306ad2fc1dab1f44760`，与上次问题快照相同。三条证据增加了正常恢复链的本地实景依据，**不覆盖“状态已稳定后再收到分配通知”或“有本地草稿/失败气泡的存活页面收到成功 clear marker”**，故 R20-REC-P2-01/02 仍开放，上一节逐项分数不变。不能用初次 peer409 正文可见代替后到通知的保留，也不能用列表清空代替聊天页本地状态清理。

新增 JSON SHA-256：

- live-clear-conflict.json：`7905ab4858c7588b2ab64fcb4aa49600d7505e023339b5bfe8aded6527c51cda`
- live-clear-lost-refusal.json：`441b868e5fc3922efe6b41e344e80a10f15c4295f1354c97a4909afea8e26682`
- live-peer-changed.json：`f0e40ec341b3415db2bfc5e33e6a587ce4cb70e088cfba763b68436448d68ea1`

正式 Java/JWT/WS 联调、真实登录/换机、TEST 部署与物理设备边界不变，未预签。本轮仅补充此报告，未改生产或测试。

**Jason 第二轮页面修复复核（2026-10-27，冻结源码与既有测试日志）**

本轮独立读取修复源码、实际测试断言及三个原始日志；未运行测试、Maven、构建或浏览器，未修改生产/测试。APP 仍以 `19f1a42b6b0dbda7ba6579aae236158f5adf7b93` 为差异基线，本次具体源码指纹列于下方。backend AppSupportService 保持上一节 `988c8870…` 快照，本轮无新增后端实现变化。未操作或初始化最终 r3 计划。

**当前判定：R20-REC-P2-01、R20-REC-P2-02 均在本地行为范围关闭，未发现新增明确 P0/P1/P2。** 此结论依赖这次实际红绿用例及源码修正，不依赖旧正常路径实景。原 8.4 / 8.6 / 8.4 低分保留为前一冻结版历史，以下为当前逐项评分，无平均分。

| 增量审查项 | 当前 /10 | 与前次的关系 |
| --- | ---: | --- |
| peer409 / clear409 精确终态识别 | **9.3** | 沿用此前已审分类及后端 HTTP 状态映射，未扩大到普通 409 |
| backend 原 clear 拒绝命令读回 | **9.3** | 源码未变，沿用 17/17 日志与精确 scope/status/reason 审查 |
| original key 与未知正文跨分配变更 | **9.2** | 前次 8.4；稳定 flush 后 unknown、下一段正文、后续编辑均保留，P2-01 关闭 |
| 旧客服只读与被拒绝正文保留 | **9.2** | 前次 8.6；稳定的 peer-refused 正文收到通知后仍在，原姓名/只读状态保持，无自动导航或 POST |
| 用户主动 restart 与目标隔离 | **9.1** | 行为未改，沿用此前源码及父线程 fixture 三条恢复链中的对应证据 |
| clear409 拒绝、刷新与再次确认 | **9.2** | 拒绝链未改；保留原 key 的未知结果与确定拒绝后新 key 重确认仍分开 |
| clear 成功后的页面数据/草稿边界 | **9.2** | 前次 8.4；管理/详情/补页水位同步页面，重复水位不清新草稿，迟到失败不复活旧气泡，P2-02 关闭 |

**P2-01 关闭：稳定 flush 后的通知不再抹去正文。**

[chat.vue:281](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:281) 现在比较本次与上次 accountBindingEpoch：真正账号绑定变化仍清理正文与失败状态，同账号 scopeInvalidated 只使私有图片/附件策略等工作失效，不清 draftText / failedHumanSend。原 key 与 outbox 的保留逻辑未回退。该路径不再依赖 saved-composer watcher 恰好排队。

实际断言逐项核对：

- `preserves unknown and its draft when the same account assignment scope changes` 在设置下一段草稿后先等待两次 Vue.nextTick，再触发通知；断言 unknown、正文、pending key 仍在。随后再编辑一字，store 中原 unknown 正文仍在，覆盖了先前“页面看似恢复、后续输入又丢恢复载荷”的风险。
- `keeps an already-flushed draft across an assignment notification` 验证已稳定普通草稿。
- 同一参数化用例的 `peer-refused` 分支验证已稳定的明确拒绝正文、failed 状态、旧姓名与只读状态，且没有自动导航/创建。
- 既有 `conversations-realtime.test.ts` 原 key 用例还验证分配变更后重新建 store、恢复 unknown，再次发送仍使用原 wire key。页面变化不会通过删除账号 pending 存储来取得绿色。

原红日志中 unknown 实际为 null、草稿实际为空；这次是对应真实失败路径修复后转绿，不是更改断言接受数据丢失。真正账号切换仍由 binding 条件和既有 owner 护卫隔离；本轮不宣称真实登录/换机已执行。

**P2-02 关闭：确认水位前进同步页面，但不抹去后续新草稿。**

store 在确实删除旧 composer 时发布该 key 的 `clearedComposerThrough`；只有 `applyUserClear` 的确认水位路径传入该值，普通导航/其他 clearComposer 调用不冒充用户清空。[chat.vue:533](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:533) 同时比较 binding、composer key 和水位前进，在同步 watcher 中清除旧 draft、failedSend、imageDraft、localHumanSend，并清理私有图片/策略；清理期间抑制反向保存。不是把任何 `saved === undefined` 都当成清空信号。账号 reset 同步清理该信号表。

三种实际用例 `clears only covered live-page mirrors on a new {management|detail|older-page} watermark and keeps later drafts/messages` 都先建立带旧附件的 unknown、未发草稿并完成两次 flush，再让不同读取入口带回 clear-through-20：

- 页面四类旧状态均为空，旧本地气泡消失，服务端新消息 30 保留；对应旧 composer 被删除。
- 原 unknown key 仍在 pending 存储，清理 UI 没有冒充发送已确认或丢弃命令恢复责任。
- 写入新正文与新附件后再次读取同一 marker，新正文/附件不被清除，保存水位为 20，旧 localSend 不回流。store 对重复 marker 的处理以及页面 `through <= previousThrough` 护卫相符。

另一个实际用例 `does not restore a pre-clear in-flight send when its failed ACK and empty readback arrive later` 覆盖发送悬挂→收到新 marker→写入新草稿→旧 ACK 失败、原 key 读回为空的时序：页面不恢复旧 failed/localHumanSend，只剩新消息和新草稿，原 key 仍保留。

[chat.vue:1536](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:1536) 在发起既有 CID 的 send 前捕获 composer 水位，在 success 与 catch 两侧都比较该水位以及 binding/CID。因此旧结果不能在水位变化后回写正文或产生页面成功反馈。**本次专门的新异步用例执行的是迟到失败 ACK + 空读回；迟到成功分支核对了源码同样的护卫，不称其为新增实跑用例。**

“原 key 仍恢复”的边界也保持：清空只发布 composer 清理信号，没有删除 pendingKeys；`reconcilePending` 仍按原 key 请求权威结果，未确认时保持，确认后经当前可见性替换并完成命令。它不会重建已不存在的旧 composer，也不会把当前新草稿替换为旧 payload。现有分配变更/reload 用例验证了原 key 重试；新 clear 用例验证原 key 保留和实际原 key 读回调用。没有把新 clear 用例描述成一次真实后端登录后的完整恢复。

**直接读取的红绿记录**

| 日志 | 实际结果 | 解释 |
| --- | --- | --- |
| [r20-page-mirrors-red.log](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/.cache/r20-page-mirrors-red.log) | 1 文件，6 failed / 127 skipped，共 133；17:15:07 开始 | 稳定 unknown 1、稳定 draft/peer-refused 2、三种 marker 来源 3，失败符合此前两项 P2 |
| [r20-page-mirrors-green.log](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/.cache/r20-page-mirrors-green.log) | 1 文件，6 passed / 127 skipped，共 133；17:16:20 开始 | 同组选测转绿；127 skipped 是本组选测边界，不算通过 |
| [r20-page-mirrors-targeted-green.log](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/.cache/r20-page-mirrors-targeted-green.log) | **8 files / 396 passed (396)**；17:19:43 开始，Duration 8.52s | 扩大定向范围通过，无 skipped 计数；是本机日志直接读回，不再仅引用主线程口述计数 |

这些是 Jason/主线程执行、由本审查者读回的结果。红绿 6 项与最终 396 项存在包含/重叠，不能相加宣称 402 项不同测试；也不是仓库完整 suite。新增测试只开放真实 script 的 imageDraft 供断言，仍运行真实 chat script/store，子 `.vue` 组件被 harness 替代，未升级成完整 DOM/原生交互证据。原 backend 初轮 fixture 红项、后端 clear-readback 红绿及 DRX3akFz 父线程实景均保留原版本归属。

**本轮冻结指纹（SHA-256）**

| 文件 | SHA-256 |
| --- | --- |
| APP src/pages/support/chat.vue | `5edea8ee6e21202ad47784a67d975f610606fbd3cb3de45754db6a5dd29173c0` |
| APP src/store/conversations.ts | `fa3d507010cd43e0e13f5be58cf1a0673b84788805eeb5cb89fa479f3b8c9c26` |
| APP src/pages/support/messages-start-entry.test.ts | `b08d66c8c27abe8e03b649852ff14ba67f2e094c44d102c1843a371fe36520d5` |
| APP src/pages/support/nova-queue-page.test.ts | `090fa0d41a637d51ae429ed62c6e304ac098d8e8c67b908542d60b10d85eb08e` |
| r20-page-mirrors-red.log | `36144eb10134bea7aa9f517d18d50aad755cd713a5f4e5ba46afc3896c524603` |
| r20-page-mirrors-green.log | `330061025fba06d844b72a55afff58b65df7a67146ff2a9f5a4e8bd1414d7ae3` |
| r20-page-mirrors-targeted-green.log | `b75688d9b5cce07d14822c9242add2e71b55c3f57e80ba95ff2c893e765c37f4` |

当前没有需要本审查者要求重跑的旧矩阵。父线程正在补修复后构建及最终实景，本报告不预签其结果；旧 DRX3akFz 只证明旧包的三条常规恢复链，不能冒充此次稳定通知/水位修复实景。正式 Java/JWT/WS 联调、真实登录/换机、TEST 部署及物理设备仍未覆盖。结论可作为当前冻结源码的行为审查输入，不替代最终 r3 计划执行、构建指纹或实景验收。

**父线程 UGGOR9u8 稳定草稿实景补证（2026-10-27）**

已直接读取 [live-stable-scope.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-stable-scope.json)，并用 view_image 目检 [r20-final-scope-draft-retained.png](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-scope-draft-retained.png)。JSON 记录构建 `index-UGGOR9u8.js`，before 与 after 均为“分配通知到达前已稳定的草稿”，并明确 `formalServer: false`；截图中 textarea 仍显示完整同一正文。

执行归属为父线程：在独立 5240 iframe 实际 WebSocket 重连后捕获连接，待草稿 DOM 稳定，再注入 `scope-invalidated / ASSIGNMENT_CHANGED(900001)` 消息事件，读取重渲染后 textarea.value。本审查者只读取记录及截图，没有操作浏览器或注入事件。**这是前端接收合成消息后的页面实景，不是真 Java WS 推送，不验证正式 WS 鉴权、账号路由或服务器投递。**

该记录补足 P2-01 中“稳定普通草稿→后到分配通知→真实 textarea 保留”的本地实景依据；不泛化成 unknown、明确拒绝正文或附件组合均已实景。这些分支仍依据上一节的实际红绿脚本/store 用例。它不代替 P2-02 的 clear marker / 迟到发送页面实景。

本次再次核对 chat.vue、conversations.ts、messages-start-entry.test.ts，分别仍为 `5edea8ee…`、`fa3d5070…`、`b08d66c8…` 的上一节完整 SHA-256；backend AppSupportService 也保持 `988c8870…`。两项 P2 的修正、owner/水位护卫未漂移，**当前行为关闭状态及相关 9.2 分维持，无新增明确阻塞**。尚未提供的新实景不提前签；真实登录、正式 JWT/WS、TEST 部署与物理设备边界不变。

`live-stable-scope.json` SHA-256：`932730c2ad8411d4e4f4f8fa707f6f2a5a8e8cca7034abdf1bf268b5df01e642`。本轮仅更新本报告，没有重复执行测试或构建、改动实现或重开视觉矩阵。

**最终有限补证与报告冻结（2026-10-27，UGGOR9u8）**

本节是当前结论，前面各轮低分、红测、证据局限和当时待补状态保留为历史。已重新核对 chat.vue、conversations.ts、delivery-policy、两份页面测试及 backend AppSupportService 的完整 SHA-256，均与上一轮冻结指纹一致；APP/backend HEAD 仍分别为 `19f1a42b6b0dbda7ba6579aae236158f5adf7b93` / `090a7e5032a8f6e781150201ce51005d554890de`，它们是差异基线，不冒充包含这些未提交改动的提交。

本次直接读取 [live-stable-scope.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-stable-scope.json)、[live-page-clear.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-page-clear.json)、[live-final-recovery.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-final-recovery.json)，并实际用 view_image 查看下面八张新增最终 PNG。所有浏览器动作、HTTP 故障代理和事件注入均由父线程执行；本审查者只读取已有证据，没有操作浏览器或发送请求。三个 JSON 都标识 `index-UGGOR9u8.js`；正常夹具位于独立 5240，三条基本恢复链使用 5244→5240 故障代理，记录明确不是正式 Java 服务。

| 有限补证 | 直接读取/目检结果 | 证据边界 |
| --- | --- | --- |
| 稳定草稿后收到分配通知 | before/after 都为“分配通知到达前已稳定的草稿”；上一节已目检对应完整截图 | 父线程向捕获的 WebSocket 对象注入事件，不是真 Java WS 推送；unknown/拒绝正文组合仍依据脚本/store 用例 |
| 活页面接收确认清空 | fixture 返回 `USER_VIEW`、`agent:1`、`DEMO-SUPPORT-1`、throughMessageId=1；after 为 draft 空、failed=0，保留“已收到你的消息，我会继续跟进。”；[清空前](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-page-clear-before.png) 可见旧失败气泡及编辑/放弃入口，[清空后](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-page-clear-after.png) 旧气泡消失、新 agent 正文和空输入可见 | 父线程模拟另一客户端 DELETE，再经夹具 incoming 触发真实 store 刷新；JSON 未保存新消息数值 ID，不能仅凭截图独立证明其 ID 大于 1，数值过滤仍由既有用例和父线程执行记录支撑 |
| 重复读取不清除新草稿 | newDraftBefore/After 都为“清空以后新写的草稿”；management/cursor/detail 及 command/dismissals 均记录 200；[重复读回后](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-repeat-marker-new-draft.png) 可见完整新草稿及 agent 消息 | JSON 未保存这些响应体；“再次读回同一 marker”归属父线程取证说明，脚本/store 三种来源用例另有相等 marker 断言。不能把 200 本身当作 marker 相等的证明 |
| clear409 拒绝→新 key 重确认 | final-recovery 记录两次 DELETE 使用不同 key；[拒绝保留](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-clear-conflict-retained.png) 可见原会话及保留提示；[重确认后](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-clear-after-reconfirm.png) 可见服务空列表，通知未读 3 仍保留 | final JSON 没有 HTTP 响应体，具体拒绝状态归属父线程故障注入记录；不扩展为正式后端联调 |
| 丢失拒绝→原 key 读回→新 key | DELETE→GET commands→DELETE；独立计算 GET key 的 SHA-256 前 16 位为 `3fb919dc1e80f12c`，与第一次 DELETE 相同，第二次不同；[读回后保留](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-clear-lost-refusal-retained.png) 可见原会话与保留提示 | 503/权威409及显式确认操作顺序按父线程取证归属；请求记录本身没有这些响应码 |
| peer409 旧窗只读→主动 restart | [旧窗口](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-peer-changed-readonly.png) 保留旧姓名和“客服更换后保留原消息”；[当前客服](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-peer-current-advisor.png) 为当前身份与空输入。JSON before/after 一致；请求记录只有原 read POST 和被拒绝 reply POST，未记录 restart 自动 create/reply | 不表述为整个流程零 POST；不以截图签署后台真实分配或新会话实际发送 |

旧 `r20-clear-*` / `r20-peer-*` 的 DRX3akFz 裁偏图片仅保留历史归属，本轮最终可见内容依据上述 `r20-final-*`。本次是交互/数据语义审查，没有据这些图重开视觉评分或宣称全站视觉通过。

**当前逐项结论（各项独立 /10，不取平均）**

| 增量项 | 最终分数 | 当前判定 |
| --- | ---: | --- |
| peer409 / clear409 精确终态识别 | **9.3** | 既有源码与测试结论延续，UGGOR9u8 基本恢复链补证受理 |
| backend 原 clear 拒绝命令读回 | **9.3** | 精确 scope/status/reason 分支及 17/17 依据延续，生产未变化 |
| original key 与未知正文跨分配变更 | **9.2** | R20-REC-P2-01 关闭；稳定 flush 红绿用例与普通草稿通知实景共同支撑 |
| 旧客服只读与被拒绝正文保留 | **9.2** | 稳定拒绝正文测试及最终旧窗/主动切换实景支撑 |
| 用户主动 restart 与目标隔离 | **9.1** | 当前客服目标、用户主动进入及无自动发送证据受理 |
| clear409 拒绝、刷新与再次确认 | **9.2** | 原 key 未知恢复与确定拒绝后新 key 重确认保持区分 |
| clear 成功后的页面数据/草稿边界 | **9.2** | R20-REC-P2-02 关闭；三来源 marker、重复 marker、迟到失败用例与最终活页面实景共同支撑 |

**两项页面 P2 已关闭，当前有限范围没有新增明确 P0/P1/P2 阻塞。** 不用三条正常路径替代异步修复依据：P2-01 的稳定 flush/未知载荷保留及 P2-02 的水位同步/迟到失败仍以前节实际红绿断言及源码护卫为基础。原 key 保留和恢复责任未因清除页面镜像而丢失；原后端 P2-A/B 结论维持。再次直接读回的 APP 日志为 8 文件 396 passed，backend 命令恢复日志为 17/17，未重新运行；首轮 199+11 仍是两批，不能合并描述成一次整套全绿。

未覆盖边界继续保留：正式 HTTP/JWT 登录鉴权及 WS 投递、TEST 部署、真实换机/物理设备未验；最终图是前端+合成 fixture/事件注入。清空后迟到成功分支本轮只有源码护卫核对，新增执行用例是迟到失败 ACK+空读回；unknown/附件等组合的完整页面实景未扩充。以上不另造待办或要求重跑旧矩阵，也不提前签署父线程后续 r3/workflow、提交或部署。

最终证据 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| live-stable-scope.json | `932730c2ad8411d4e4f4f8fa707f6f2a5a8e8cca7034abdf1bf268b5df01e642` |
| live-page-clear.json | `6ffc448a5f9dd93b0a079559e8f2470c918c6103481a14f8ec3ff0ccde8214ce` |
| live-final-recovery.json | `aeeaa102678ac1ec96b86a754c2cf826f72b82767fd9e170c3306dd3d4aeef86` |

**报告冻结：本次仅更新此文件，保留所有旧低分和失败历史；没有改动生产/测试、运行检查/构建或操作浏览器。完成保存及指纹读回后停止写入，不等待其他图片，不在父线程 workflow 期间继续变更报告。报告文件最终 SHA-256 随本次交付回复提供。**
