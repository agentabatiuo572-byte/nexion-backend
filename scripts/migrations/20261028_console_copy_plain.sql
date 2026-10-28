-- Plain-language operator copy for seeded rows the console renders verbatim.
-- Trial and check-in copy is reseeded by the earlier startup migrations; this
-- file covers rows those seeds never overwrite. Each statement only rewrites
-- the exact text shipped by earlier seeds, so operator-edited copy is never
-- overwritten. Safe to reapply. The tables may be created lazily by the
-- application, so each statement runs only when its table exists.

-- H2 daily free-seat cap: its seed is insert-only, so existing rows keep the old note.
UPDATE nx_growth_trial_policy
   SET description='App 赚取页顶部免费试用卡每天的名额上限；今天还剩多少由系统按已领取数自动计算',updated_at=NOW()
 WHERE policy_key='seatsLeftToday'
   AND description='5173 赚取页顶部免费试用卡的每日名额上限；今日剩余由服务端按日期和已领取数计算'
   AND is_deleted=0;

-- I2 market push template, one language at a time.
SET @console_copy_sql := IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_nova_template') > 0,
  'UPDATE nx_nova_template SET body_zh=''行情有新变化，点开看看。'',updated_at=NOW() WHERE channel_key=''market_event'' AND body_zh=''市场曲线已有新的服务端事实。'' AND is_deleted=0',
  'SELECT 1');
PREPARE console_copy_stmt FROM @console_copy_sql; EXECUTE console_copy_stmt; DEALLOCATE PREPARE console_copy_stmt;

SET @console_copy_sql := IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_nova_template') > 0,
  'UPDATE nx_nova_template SET body_vi=''Thị trường có biến động mới. Nhấn để xem.'',updated_at=NOW() WHERE channel_key=''market_event'' AND body_vi=''Đường cong thị trường có dữ liệu mới từ máy chủ.'' AND is_deleted=0',
  'SELECT 1');
PREPARE console_copy_stmt FROM @console_copy_sql; EXECUTE console_copy_stmt; DEALLOCATE PREPARE console_copy_stmt;

SET @console_copy_sql := IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_nova_template') > 0,
  'UPDATE nx_nova_template SET body_en=''The market has moved. Tap to see.'',updated_at=NOW() WHERE channel_key=''market_event'' AND body_en=''The market curve has a new server fact.'' AND is_deleted=0',
  'SELECT 1');
PREPARE console_copy_stmt FROM @console_copy_sql; EXECUTE console_copy_stmt; DEALLOCATE PREPARE console_copy_stmt;

-- I2 social event distribution name (matches the event-type options).
SET @console_copy_sql := IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_nova_social_distribution') > 0,
  'UPDATE nx_nova_social_distribution SET dist_name=''创世节点成交'',updated_at=NOW() WHERE dist_key=''genesis'' AND dist_name=''Genesis 成交'' AND is_deleted=0',
  'SELECT 1');
PREPARE console_copy_stmt FROM @console_copy_sql; EXECUTE console_copy_stmt; DEALLOCATE PREPARE console_copy_stmt;

-- K4 initial score model note.
SET @console_copy_sql := IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_admin_risk_score_model') > 0,
  'UPDATE nx_admin_risk_score_model SET reason=''系统初始评分规则'' WHERE reason=''K4 canonical initial model'' AND is_deleted=0',
  'SELECT 1');
PREPARE console_copy_stmt FROM @console_copy_sql; EXECUTE console_copy_stmt; DEALLOCATE PREPARE console_copy_stmt;
