-- H3 owns the category and direct App destination for every day-one/weekly mission.
-- Existing rows are backfilled only while either new column is still empty, so
-- later PC edits survive the startup runner being executed again.

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_mission'
      AND COLUMN_NAME = 'mission_category') = 0,
  'ALTER TABLE nx_mission ADD COLUMN mission_category VARCHAR(32) NULL AFTER mission_type',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_mission'
      AND COLUMN_NAME = 'action_route') = 0,
  'ALTER TABLE nx_mission ADD COLUMN action_route VARCHAR(255) NULL AFTER mission_category',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE nx_mission
   SET mission_category = CASE mission_code
     WHEN 'bind_bank_card' THEN 'WALLET'
     WHEN 'setup_profile' THEN 'IDENTITY'
     WHEN 'invite_friend' THEN 'SOCIAL'
     WHEN 'H3_REFERRAL_SETTLED' THEN 'SOCIAL'
     WHEN 'weekly_t2_invite_friend' THEN 'SOCIAL'
     WHEN 'H3_COMMISSION_UNLOCKED' THEN 'SOCIAL'
     WHEN 'weekly_t1_nex_v2_lock' THEN 'WALLET'
     WHEN 'weekly_t1_topup_balance' THEN 'WALLET'
     WHEN 'weekly_t1_stake_fallback' THEN 'WALLET'
     WHEN 'weekly_t2_reinvest' THEN 'WALLET'
     WHEN 'weekly_t2_stake_small' THEN 'WALLET'
     WHEN 'weekly_t2_nex_swap' THEN 'WALLET'
     WHEN 'weekly_t2_top_up_small' THEN 'WALLET'
     WHEN 'view_product_roi' THEN 'RECOMMEND'
     WHEN 'H3_FIRST_ORDER_STARTED' THEN 'RECOMMEND'
     WHEN 'weekly_t1_buy_genesis' THEN 'RECOMMEND'
     WHEN 'weekly_t1_buy_additional_hw' THEN 'RECOMMEND'
     WHEN 'weekly_t1_tradein_upgrade' THEN 'RECOMMEND'
     WHEN 'weekly_t1_upgrade_s1_to_pro_v2' THEN 'RECOMMEND'
     WHEN 'weekly_t1_buy_first_box' THEN 'RECOMMEND'
     ELSE 'EXPLORE'
   END
 WHERE mission_category IS NULL OR TRIM(mission_category) = '';

UPDATE nx_mission
   SET action_route = CASE mission_code
     WHEN 'bind_bank_card' THEN '/pages/me/wallet-cards-new'
     WHEN 'visit_earn' THEN '/pages/earn/earn'
     WHEN 'visit_store' THEN '/pages/store/store'
     WHEN 'view_product_roi' THEN '/pages/store/detail?id=stellarbox-s1'
     WHEN 'setup_profile' THEN '/pages/me/profile'
     WHEN 'invite_friend' THEN '/pages/team/team'
     WHEN 'H3_FIRST_ORDER_STARTED' THEN '/pages/store/store'
     WHEN 'H3_REFERRAL_SETTLED' THEN '/pages/team/team'
     WHEN 'H3_LEARNING_COMPLETED' THEN '/pages/learn/courses'
     WHEN 'H3_DEVICE_ACTIVATED' THEN '/pages/me/devices'
     WHEN 'weekly_t1_nex_v2_lock' THEN '/pages/staking/staking'
     WHEN 'weekly_t1_buy_genesis' THEN '/pages/genesis/genesis'
     WHEN 'weekly_t1_buy_additional_hw' THEN '/pages/store/store'
     WHEN 'weekly_t1_tradein_upgrade' THEN '/pages/store/store'
     WHEN 'weekly_t1_upgrade_s1_to_pro_v2' THEN '/pages/store/store'
     WHEN 'weekly_t1_subscribe_premium' THEN '/pages/earn/earn'
     WHEN 'weekly_t1_buy_first_box' THEN '/pages/store/store'
     WHEN 'weekly_t1_topup_balance' THEN '/pages/me/wallet-topup'
     WHEN 'weekly_t1_stake_fallback' THEN '/pages/staking/staking'
     WHEN 'H3_COMMISSION_UNLOCKED' THEN '/pages/team/commissions'
     WHEN 'weekly_t2_invite_friend' THEN '/pages/team/team'
     WHEN 'weekly_t2_reinvest' THEN '/pages/me/wallet-repurchase'
     WHEN 'weekly_t2_stake_small' THEN '/pages/staking/staking'
     WHEN 'weekly_t2_nex_swap' THEN '/pages/me/wallet-exchange'
     WHEN 'weekly_t2_top_up_small' THEN '/pages/me/wallet-topup'
     WHEN 'weekly_t2_browse_store' THEN '/pages/store/store'
     WHEN 'weekly_t2_ai_jobs_50' THEN '/pages/earn/earn'
     WHEN 'weekly_t2_genesis_browse' THEN '/pages/genesis/marketplace'
     ELSE '/pages/missions/missions'
   END
 WHERE action_route IS NULL OR TRIM(action_route) = '';

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_mission'
      AND COLUMN_NAME = 'mission_category'
      AND (IS_NULLABLE <> 'NO' OR COLUMN_DEFAULT <> 'EXPLORE')) > 0,
  'ALTER TABLE nx_mission MODIFY COLUMN mission_category VARCHAR(32) NOT NULL DEFAULT ''EXPLORE''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_mission'
      AND COLUMN_NAME = 'action_route'
      AND (IS_NULLABLE <> 'NO' OR COLUMN_DEFAULT <> '/pages/missions/missions')) > 0,
  'ALTER TABLE nx_mission MODIFY COLUMN action_route VARCHAR(255) NOT NULL DEFAULT ''/pages/missions/missions''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
