# S4 维护与活动引擎交接

本文件只描述实现和待运行边界，不声明 S4 全部验收通过。以主线固定快照后的 Maven、MySQL、HTTP 和跨端实景报告为准。

## 接口与表

- `SupportMaintenanceService.executed(customer, assignment, messageId, commandKey)` 必须在成功人工 MAINTENANCE 消息事务内调用；默认开关开启，停止时拒绝维护发送并回滚消息。执行永久以 messageId 唯一，同 OPEN 周期不重置 baseline。
- `change(customer, enabled, reason, expectedAssignmentId, expectedVersion, key)` 复用保留成功结果的命令机制，scope 为 `M3_MAINTENANCE:{actor}`；本人资格、当前归属、偏好版本、理由与审计同时检查。恢复不建立周期。
- `history(customer,pageNum,pageSize)` 在读事务内校验当前顾问/主管，返回分页 cycles/executions 和各自 total；时间为 UTC offset，保留原顾问归因。
- 同步监听 `SupportAssignmentChanged` 关闭 OPEN 为 TRANSFERRED，任何异常回滚原转绑事务。
- 独立迁移 `20260929_support_maintenance_s4.sql` 创建 preference、cycle、execution、activity_state、activity_event、activity_coverage 六表；不重写原消息、邀请、财务或旧时间列。
- execution 上的 `(assignment_id,executed_at)` 索引支持工作台按当前归属查询最后执行时间。隔离 MySQL 实际 EXPLAIN 在补索引前为 `type=ALL / possible_keys=NULL`；迁移同时含 information_schema 检查与增量 ALTER，已有 S4 表重复执行也能补索引。最终应用后的 EXPLAIN 与二跑结果由主线记录。

## 活动源与水位证明

启用源只有 `AppUserAuthService` 中通过全部校验并完成 session 创建的交互登录：密码登录、短信 OTP 完整登录、二步验证完整登录、强制改密后的完整登录。sourceRef 是 `INTERACTIVE_LOGIN:` 加服务端新建 sessionChainId，UUID 不接受外部事件名/时间。新事件和 session 在同一事务提交，活动写失败会抛出并回滚登录；没有异步消费者。

注册自动发 session 显式排除。当前 OAuth 是 development/mock exchange，也复用注册 session，明确排除。refresh、JWT 过滤器、客服聊天、read、轮询、SSE/WS 连接和后台业务未连接此源。未来 OAuth 真交互源需另行给出可靠业务成功证据，不能直接把共用 session 方法全部当登录。

每客户先锁 `nx_user`，再读取当前 activity/cycle/preference 的锁定最新版本，活动 seq 在同一客户锁内加一。只 seq 高于首次执行 baseline、发生时间不早于首次执行、开关开启且同归属的 OPEN 可成功。时间同微秒允许由客户事务锁与 seq 决定先后；旧 sourceRef 重放立即返回，不重新评价当前周期。

捕获持有全局 coverage 行共享锁直到提交；checkpoint 仅独占该行，不拿客户/顾问/归属锁。它等所有已开始捕获的事务提交，再持久推进 observedThroughAt，提交后工作台另开一致读快照，以该水位过滤事件。后续捕获时间严格大于持久水位（数据库 UTC 与水位加 1 微秒取较大值），不以读取时墙钟伪造已消费水位。checkpoint 不与客户锁形成反序；调用它之前不得在外层持有客户/归属锁。

数据库与应用服务中断时没有成功登录而漏活动的问题：成功登录必须提交该捕获，数据库失败令登录失败；客户端丢响应不等于数据库丢事件。旧认证程序与新程序混跑不满足此证明。迁移/启用必须先停旧认证写入，再部署完整同步捕获；首次迁移时间是无历史覆盖起点。若发生绕过捕获的旧写入、恢复了较旧数据库或人工损坏数据，运维必须在停写窗口将 coverage_start_at、observed_through_at 重设为重新启用时的数据库 UTC，重新积累 D/W；不得保留旧 complete 标签。迁移重跑 INSERT IGNORE 不会重置正常连续区间。

## 运行命令与边界

已有 JDK17 和 Maven，主线在隔离环境变量加载后统一运行：

```powershell
& D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd '-Dtest=SupportMaintenanceServiceTest,AppUserAuthServiceTest,AppUserOAuthServiceTest' test
& D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd '-Dtest=SupportMaintenanceMySqlS4Test' test
```

MySQL 测试须提供 `S4_EVIDENCE_DIR`，使用 18129，连接必须是 127.0.0.1:33329/cs_redesign；前提是主线已应用三份 S4 迁移。测试新建随机客户/顾问，通过真实消息行 + human.prepare/committed 写入，覆盖执行重放、停止恢复、转绑、回滚、activity/stop 两种锁顺序、send/transfer 两种锁顺序、checkpoint 等待未提交捕获、断档恢复及 D 扩大。断档时钟为测试显式 SQL 夹具，不能当作真实经过了多天；全局覆盖夹具 finally 恢复起点，必须串行执行。

本子任务不启动数据库/服务、不读取仓外凭据、不执行生产 SQL，不替代主线 HTTP 登录、六指标、图片、客户端、浏览器验收。done-review 六维需由最终集成逐项收口：当前仅源码回读与可运行测试已提供，真实提交读回、并发运行和界面实景尚需主线结果。
