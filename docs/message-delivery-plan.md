# APP 消息投递

范围：5180 整合版对应后端。复用 outbox 和 consumer delivery 回执；不改变资金、风控、权限及人工服务政策。

不变量：业务事务回滚不得留下通知；投递失败只重试通知消费，不能重做资金操作；接收账号必须来自持久业务归属或服务端权威事件；通知只提供详情导航，不能执行金融动作。通知不可包含内部风控原因、分值、地址或渠道凭证。

实施顺序：
1. 支付通知直接 DELIVERED，三语及实际路由；迁移仅修复已确认业务前缀的历史 PENDING，不改已读/删除记录。
2. 六键偏好保留，system 服务端强制开启；资金/安全必收查询、计数和读操作一致；Nova 类按实际业务类别选偏好。
3. 既有真实事件投影成通知，事件回执幂等；补齐提现付款及账户冻结的真实状态事件；内部重试不刷屏。
4. 回归：三语、归属、重复事件、失败重试、回滚、偏好、outbox 分发表与退出 record-only；主线独立 Java 审查及 APP 实景。

验证命令：配置本机 JDK 17 后，Maven 定向执行 AppNotificationServiceTest、AppNotificationControllerSecurityTest、NotificationPreferenceServiceTest、NotificationPreferenceCriticalDeliveryContractTest、BusinessNotificationEventConsumerTest、D2WithdrawalLifecycleEventConsumerTest、WithdrawalPayoutExecutorTest、WithdrawalPayoutCallbackServiceTest、HdPayPayoutTransactionsTest、OutboxDispatchCoverageTest、OutboxRecordOnlyRetirementTest、OpsConsoleArchitectureTest。MySQL 仅使用隔离测试 schema。

原生系统推送、后台页面和旧 Nova 频道开关不在本次修改范围。

## 已验证范围与验收入口

- 支付直投：HDPAY 充值到账、后台解绑/要求换绑支付卡；三语与现有钱包/支付卡路由。
- 新通知消费：提现 submitted/approved/rejected/delayed/frozen/unfrozen/refunded/confirmed/processing/payout_held/account_frozen/account_restored；checkout.completed、order.refunded、device.activated/deactivated、quest.claimed、event.claimed、daily.milestone_claimed、auth.password_reset_completed。
- 转账 producer：Cregis submitted/orphaned/ambiguous/terminal；HDPay prepare/return-for-review/hold，原终态 producer 保留；C2 在事务锁定受影响提现后发 account_frozen/account_restored。review_due 和内部重试不刷屏。
- Nova 保留已配置频道/模板/频率/冷却，佣金、团队、质押、市场、Genesis 按真实源事件选原六键偏好；未产生的升级/可领取资格事件不补造。
- 迁移：20261002_app_business_notifications.sql 注册进标准启动序列；只在 localhost:33329/cs_redesign 应用。通知数据库回归使用独立临时 schema，销毁后不留测试记录。
- 验收：136 项定向回归全部通过，包含真实数据库的失败回滚、再次消费及重复状态去重；独立 Java 审查、HTTP 和 5180 三语实景已完成。
- 全量记录为 6672 项、1 项 IPv6 环境失败、262 项跳过；2026-10-03 在源码与配置不变的情况下仅复测该失败方法，1 项通过、0 失败、0 跳过。这是原全量结果加失败项复测，不代表重新执行了全量或覆盖了原跳过项。

## 对抗性复验

- Nova 合法频道名存在下划线通配与短前缀碰撞；使用现有业务回执的精确 channel/sourceEvent 对应消息，保留 fanout 数字批次尾段，不能靠裸 LIKE 判频道。点击来源同样回查持久频道，不能从新的业务分类反推。
- 清理已读保留 critical 与 nx_notification_cap_rule 中启用的 locked 等级；消息表没有单条 locked 字段。
- AppBusinessWithdrawalEventMySqlTest 真 finalizer + EventOutboxService + 生产 schema 2 项通过（确认/退款两分支、outbox 故障资金回滚、orphan hold、C2 冻结/恢复），HdPayPayoutEventMySqlTest 4 项通过，证据 target/message-notifications-real-finance.log。
- 真实已读写入依赖正式 I3 迁移 `20260722_i3_a4_event_closure.sql`，已加入标准启动序列。HTTP 数据库回归覆盖缺 schema 的 422 回滚、迁移重放后的单条/批量已读、CTA 与 Nova 点击、跨账号及 ADMIN 拒绝；首个 CTA 的初始化独立于业务事务并先于首次查询，故障后回执和事件必须一起回滚。
