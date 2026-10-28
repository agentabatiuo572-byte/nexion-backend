-- Plain-language operator copy for seeded rows the console renders verbatim.
-- Each statement only rewrites the exact text shipped by earlier seeds, so
-- operator-edited copy is never overwritten. Safe to reapply.
START TRANSACTION;

-- H2 trial settings (descriptions and names shown on the trial settings page).
UPDATE nx_growth_trial_policy SET description='只影响新开始的试用',updated_at=NOW()
 WHERE description='新建试用锁定' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='用户购买时按最新设置计算',updated_at=NOW()
 WHERE description='购买时由服务端重算' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='只由系统判定，不对外显示',updated_at=NOW()
 WHERE description='仅服务端裁决' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='从「商品管理与上架条件」中在售的实物商品里选择',updated_at=NOW()
 WHERE description='从 E1 在售实物 SKU 选择' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='自动跟随所选商品的售价',updated_at=NOW()
 WHERE description='由 E1 目标商品售价同步' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='每次申请试用时检查',updated_at=NOW()
 WHERE description='实时资格校验' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='由「运营阶段设置」决定，这里只能查看',updated_at=NOW()
 WHERE description='由 H1 当前阶段派发，只读' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='App 赚取页顶部免费试用卡每天的名额上限；今天还剩多少由系统按已领取数自动计算',updated_at=NOW()
 WHERE policy_key='seatsLeftToday' AND description='5173 赚取页顶部免费试用卡的每日名额上限；今日剩余由服务端按日期和已领取数计算' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='用户在试用期内提前购买时，在试用抵扣之外再减这个金额（不超过应付金额，填 0 表示关闭）',updated_at=NOW()
 WHERE policy_key='earlyPurchaseDiscountUSD' AND description='试用原期内转购：补齐完整试用权益并再减此金额（封顶至应付，0=关闭）' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET policy_name='试用每日收益 USDT',updated_at=NOW()
 WHERE policy_key='shadowDailyUSD' AND policy_name='每日影子收益 USDT' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET policy_name='试用每日收益 UVT',updated_at=NOW()
 WHERE policy_key='shadowDailyNEX' AND policy_name='每日影子收益 NEX' AND is_deleted=0;

-- H5 check-in rules.
UPDATE nx_growth_checkin_rule SET rule_name='幸运奖励最高倍率',updated_at=NOW()
 WHERE rule_key='luckyMultiplierMax' AND rule_name='Lucky最高倍率' AND is_deleted=0;
UPDATE nx_growth_checkin_rule SET description='随机奖励倍率的上限',updated_at=NOW()
 WHERE description='服务端RNG倍率上限' AND is_deleted=0;
UPDATE nx_growth_checkin_rule SET description='抽中这一档的概率（百分比）',updated_at=NOW()
 WHERE description='服务端RNG概率百分比' AND is_deleted=0;
UPDATE nx_growth_checkin_rule SET rule_name='断签补救卡张数',updated_at=NOW()
 WHERE rule_key='revive' AND rule_name='Streak Saver张数' AND is_deleted=0;

COMMIT;

-- I2 market push template and the K4 initial model note live in tables the
-- application may create lazily, so only touch them when they exist.
SET @console_copy_sql := IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_nova_template') > 0,
  'UPDATE nx_nova_template SET body_zh=''行情有新变化，点开看看。'',body_vi=''Thị trường có biến động mới. Nhấn để xem.'',body_en=''The market has moved. Tap to see.'',updated_at=NOW() WHERE channel_key=''market_event'' AND body_zh=''市场曲线已有新的服务端事实。'' AND is_deleted=0',
  'SELECT 1');
PREPARE console_copy_stmt FROM @console_copy_sql; EXECUTE console_copy_stmt; DEALLOCATE PREPARE console_copy_stmt;

SET @console_copy_sql := IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_admin_risk_score_model') > 0,
  'UPDATE nx_admin_risk_score_model SET reason=''系统初始评分规则'' WHERE reason=''K4 canonical initial model'' AND is_deleted=0',
  'SELECT 1');
PREPARE console_copy_stmt FROM @console_copy_sql; EXECUTE console_copy_stmt; DEALLOCATE PREPARE console_copy_stmt;
