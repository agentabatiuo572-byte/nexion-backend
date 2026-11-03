SET NAMES utf8mb4;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_daily_check_in' AND column_name='power_up_multiplier'),'SELECT 1',
  'ALTER TABLE nx_daily_check_in ADD COLUMN power_up_multiplier DECIMAL(12,4) NOT NULL DEFAULT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

START TRANSACTION;
UPDATE nx_streak_power_up SET status=0,is_deleted=1 WHERE power_up_code='STAKING_APY' AND (status<>0 OR is_deleted<>1);
INSERT INTO nx_streak_power_up
  (power_up_code,power_up_name,i18n_key,target_path,badge_achievement_code,unlock_streak_days,
   effect_type,effect_value,duration_days,sort_order,status,is_deleted)
SELECT 'CHECKIN_BOOST','签到加成','streak.checkin_boost','/daily','',30,'CHECKIN_MULTIPLIER','1.5',0,30,1,0
 WHERE NOT EXISTS(SELECT 1 FROM nx_streak_power_up WHERE power_up_code='CHECKIN_BOOST');
COMMIT;
