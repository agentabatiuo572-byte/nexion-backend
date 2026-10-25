# H4 活动提醒与个人奖池结果补充规则（2026-10-25）

本文件同步本轮功能修复的产品规则；App/H5 主 PRD 由对应维护者同步。

## 活动开始提醒

用户在活动预告期订阅后，活动开始且仍开放时，由现有 H4 每 60 秒调度发出一次站内提醒。提醒沿用账户语言，使用活动已发布的标题、说明及活动详情入口。相同活动/账户仅有一条通知。

站内落地成功使用通知中心既有 `DELIVERED` 状态并记录 `pushed_at`；不把 `SKIPPED` 当作送达，不进入外部 push 队列。公开通知列表与未读数继续服从既有用户通知偏好；活动提醒按系统通知偏好处理，不提升为 critical。

通知写入与订阅 `notified_at` 更新在同一事务。写入失败、唯一键冲突或订阅状态更新失败时不伪标已通知；调度仍可在后续周期重试。提醒已成功时重复调度不新增通知。

历史补丁 `20261025_h4_reminder_visibility.sql` 只处理与真实 H4 订阅的 event_code/user_id 精确对应、订阅已 notified、通知未删除且为 EVENT/SKIPPED 的记录。补丁保留原内容、created_at、read_flag/read_at 与偏好，只修正站内送达状态及缺失送达时间；重复执行无额外更新，不改变其他 SKIPPED，不补发外部推送。

H4 的 starts_at/ends_at/notified_at 继续存 UTC 墙钟时间；通知 created_at/updated_at/pushed_at 沿用现有通知表 NOW() 的存储口径，历史修复不改原 created_at。

## 区域与持币活动的本人到账金额

活动详情顶层新增 `myRewardAmount`，单位 USDT。只为 regional/holding 从本活动、当前鉴权用户的 `nx_growth_event_pool_result.status='PAID'` 记录读取 reward_usdt。已到账的合法 0 保留为 0；未到账、无记录、其他活动或无托管规则均为 null。不得从地区总额、池规模或进度推算本人到账。

原 `results`、地区榜单与地区奖励总额保留；新增字段不触发结算、领取或账务写入。区域活动页面不应把固定的 0/1 投影当作个人完成进度，个人实际到账与地区总额分别展示。

## 正常只读取证入口

- 用户：`GET /api/notifications?limit=100` 与活动详情的 reminderSubscribed / myRewardAmount。
- PC：`GET /api/admin/users/profiles/{userId}/360` 的 notifications.records，可读含 SKIPPED 在内的最新 20 条未删除通知；需 user_c1hub_read 及可见增长分区的角色。
- PC 时间证据：`GET /api/admin/users/profiles/{userId}/notifications/{notificationId}/time-evidence`；另需 platform_a4_read。

真实 TEST 发布后须核对原提醒恢复可见且重复 tick 仍唯一。隔离 MySQL 回归与本地测试不是线上送达证明。
