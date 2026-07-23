-- I2 Nova closure: reconcile the server-owned cadence catalog to the PRD 10-key contract.
-- Legacy rows are soft-deleted rather than destroyed so the cutover remains auditable.

USE nexion;

UPDATE nx_nova_template
   SET is_deleted = 1,
       operator = 'system:migration',
       reason = 'I2 canonical 10-key cutover: legacy template quarantined',
       updated_at = NOW()
 WHERE is_deleted = 0
   AND channel_key NOT IN (
       'welcome','market','upgrade','dailySummary','tradein',
       'social','eventClaim','wrapped','taskLockMonthly','quest');

UPDATE nx_nova_channel
   SET is_deleted = 1,
       enabled = 0,
       operator = 'system:migration',
       reason = 'I2 canonical 10-key cutover: legacy channel quarantined',
       updated_at = NOW()
 WHERE is_deleted = 0
   AND channel_key NOT IN (
       'welcome','market','upgrade','dailySummary','tradein',
       'social','eventClaim','wrapped','taskLockMonthly','quest');

INSERT INTO nx_nova_channel (
    channel_key, channel_name, trigger_rule, tick_rule, cooldown_rule,
    phase_keyed, ctr_pct, target_ctr_pct, enabled, sort_order,
    operator, reason, created_at, updated_at, is_deleted)
VALUES
    ('welcome', '欢迎与玩法解释', '注册成功后首次触达', '8s', '24h', '', 0, 25, 1, 10,
     'system:migration', 'CANONICAL_I2_CHANNEL welcome', NOW(), NOW(), 0),
    ('market', '市场氛围播报', '全网算力或 NEX 行情满足播报条件', '12 min', '30 min', '', 0, 25, 1, 20,
     'system:migration', 'CANONICAL_I2_CHANNEL market', NOW(), NOW(), 0),
    ('upgrade', '设备升级建议', '用户设备组合满足升级建议条件', '15 min', '60 min', '', 0, 25, 1, 30,
     'system:migration', 'CANONICAL_I2_CHANNEL upgrade', NOW(), NOW(), 0),
    ('dailySummary', '每日收益总结', '完成日内任务后汇总触达', '25 min', '25 min', '', 0, 25, 1, 40,
     'system:migration', 'CANONICAL_I2_CHANNEL dailySummary', NOW(), NOW(), 0),
    ('tradein', '以旧换新提醒', '符合 E3 以旧换新资格时触达', '15 min', '60 min',
     'P1-P2 skip / P3-P4 60 min / P5-P6 24h', 0, 25, 1, 50,
     'system:migration', 'CANONICAL_I2_CHANNEL tradein follows H1', NOW(), NOW(), 0),
    ('social', '真实业务动态', '从已验证且未过期的真实事件池按权重抽样', '20 min', '30 min', '', 0, 25, 1, 60,
     'system:migration', 'CANONICAL_I2_CHANNEL social', NOW(), NOW(), 0),
    ('eventClaim', '活动奖励催领', 'H4 活动奖励进入可领取态时触达', '15 min', '60 min', '', 0, 25, 1, 70,
     'system:migration', 'CANONICAL_I2_CHANNEL eventClaim', NOW(), NOW(), 0),
    ('wrapped', '半年与年度回顾', '服务端回顾周期到达且用户满足召回条件', '1d', '30d', '', 0, 25, 1, 80,
     'system:migration', 'CANONICAL_I2_CHANNEL wrapped', NOW(), NOW(), 0),
    ('taskLockMonthly', '月度任务锁定召回', 'H3 月度任务锁定累计条件满足时触达', '30 min', '30d',
     'P1-P2 30d / P3-P4 7d / P5-P6 84h', 0, 25, 1, 90,
     'system:migration', 'CANONICAL_I2_CHANNEL taskLockMonthly follows H1', NOW(), NOW(), 0),
    ('quest', '任务宽限与过期召回', 'H3 任务状态进入宽限、过期或周刷新时触达', '5 min', '7d', '', 0, 25, 1, 100,
     'system:migration', 'CANONICAL_I2_CHANNEL quest', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
    channel_name = VALUES(channel_name),
    trigger_rule = VALUES(trigger_rule),
    tick_rule = VALUES(tick_rule),
    cooldown_rule = VALUES(cooldown_rule),
    phase_keyed = VALUES(phase_keyed),
    target_ctr_pct = VALUES(target_ctr_pct),
    enabled = VALUES(enabled),
    sort_order = VALUES(sort_order),
    operator = VALUES(operator),
    reason = VALUES(reason),
    updated_at = NOW(),
    is_deleted = 0;

INSERT INTO nx_nova_template (
    channel_key, template_name, cta, version,
    title_zh, body_zh, title_vi, body_vi, title_en, body_en,
    status, operator, reason, created_at, updated_at, is_deleted)
VALUES
    ('welcome', '欢迎模板', '/earn', 'v1',
     '欢迎来到 Nexion', '从第一项收益任务开始了解平台。',
     'Chào mừng đến Nexion', 'Hãy bắt đầu với nhiệm vụ thu nhập đầu tiên.',
     'Welcome to Nexion', 'Start with your first earning task.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('market', '市场动态模板', '/earn', 'v1',
     '市场动态', 'NEX 市场出现了值得关注的新变化。',
     'Diễn biến thị trường', 'Thị trường NEX vừa có thay đổi đáng chú ý.',
     'Market update', 'There is a notable change in the NEX market.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('upgrade', '升级建议模板', '/devices', 'v1',
     '设备升级建议', '你的设备组合出现了新的升级选择。',
     'Gợi ý nâng cấp thiết bị', 'Hệ thống vừa tìm thấy lựa chọn nâng cấp mới cho bạn.',
     'Device upgrade suggestion', 'A new upgrade option is available for your fleet.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('dailySummary', '每日总结模板', '/earn', 'v1',
     '今日收益总结', '查看今天的任务和收益进度。',
     'Tổng kết thu nhập hôm nay', 'Xem tiến độ nhiệm vụ và thu nhập hôm nay.',
     'Daily earning summary', 'Review today’s task and earning progress.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('tradein', '以旧换新模板', '/devices', 'v1',
     '设备换新机会', '你当前有设备符合以旧换新条件。',
     'Cơ hội đổi thiết bị', 'Bạn có thiết bị đủ điều kiện đổi mới.',
     'Trade-in opportunity', 'One of your devices is eligible for trade-in.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('social', '真实业务动态模板', 'NONE', 'v1',
     'Nexion 真实动态', '{actor} 在 {city} 完成 {amount}',
     'Hoạt động thực trên Nexion', '{actor} tại {city} vừa hoàn tất {amount}',
     'Verified Nexion activity', '{actor} in {city} completed {amount}',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('eventClaim', '活动催领模板', '/earn', 'v1',
     '奖励待领取', '你有一项活动奖励等待领取。',
     'Phần thưởng đang chờ', 'Bạn có phần thưởng sự kiện đang chờ nhận.',
     'Reward ready to claim', 'An event reward is ready for you to claim.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('wrapped', '周期回顾模板', '/me/weekly', 'v1',
     '你的 Nexion 回顾', '回顾这一周期的设备、收益和任务成果。',
     'Nhìn lại hành trình Nexion', 'Xem lại thiết bị, thu nhập và thành tích nhiệm vụ của bạn.',
     'Your Nexion recap', 'Review your devices, earnings, and task achievements.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('taskLockMonthly', '月度任务锁定模板', '/earn', 'v1',
     '月度任务进度提醒', '本月仍有任务进度等待完成。',
     'Nhắc tiến độ nhiệm vụ tháng', 'Bạn vẫn còn tiến độ nhiệm vụ cần hoàn tất trong tháng này.',
     'Monthly task reminder', 'You still have monthly task progress to complete.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0),
    ('quest', '任务宽限模板', '/earn', 'v1',
     '任务时间提醒', '你的任务已进入关键时间窗口。',
     'Nhắc thời gian nhiệm vụ', 'Nhiệm vụ của bạn đã bước vào khoảng thời gian quan trọng.',
     'Quest timing reminder', 'Your quest has entered an important time window.',
     'PUBLISHED', 'system:migration', 'I2 canonical localized template', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
    template_name = VALUES(template_name),
    cta = VALUES(cta),
    version = VALUES(version),
    title_zh = VALUES(title_zh),
    body_zh = VALUES(body_zh),
    title_vi = VALUES(title_vi),
    body_vi = VALUES(body_vi),
    title_en = VALUES(title_en),
    body_en = VALUES(body_en),
    status = 'PUBLISHED',
    operator = VALUES(operator),
    reason = VALUES(reason),
    updated_at = NOW(),
    is_deleted = 0;

CREATE TABLE IF NOT EXISTS nx_nova_social_runtime_slot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  slot_key VARCHAR(128) NOT NULL,
  lease_owner VARCHAR(128) NOT NULL,
  lease_until DATETIME NOT NULL,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_nova_social_runtime_slot (slot_key),
  KEY idx_nova_social_runtime_lease (completed_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
