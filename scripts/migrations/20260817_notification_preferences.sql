-- Account-scoped notification category switches. Sound and haptics deliberately
-- remain device-local and are not stored here.
CREATE TABLE IF NOT EXISTS nx_user_preference (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  sound_enabled TINYINT NOT NULL DEFAULT 1,
  haptics_enabled TINYINT NOT NULL DEFAULT 1,
  notify_commission TINYINT NOT NULL DEFAULT 1,
  notify_team TINYINT NOT NULL DEFAULT 1,
  notify_staking TINYINT NOT NULL DEFAULT 1,
  notify_market TINYINT NOT NULL DEFAULT 1,
  notify_genesis TINYINT NOT NULL DEFAULT 1,
  notify_system TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_preference_user (user_id),
  KEY idx_user_preference_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_preference' AND COLUMN_NAME='notify_commission')=0,
  'ALTER TABLE nx_user_preference ADD COLUMN notify_commission TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_preference' AND COLUMN_NAME='notify_team')=0,
  'ALTER TABLE nx_user_preference ADD COLUMN notify_team TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_preference' AND COLUMN_NAME='notify_staking')=0,
  'ALTER TABLE nx_user_preference ADD COLUMN notify_staking TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_preference' AND COLUMN_NAME='notify_market')=0,
  'ALTER TABLE nx_user_preference ADD COLUMN notify_market TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_preference' AND COLUMN_NAME='notify_genesis')=0,
  'ALTER TABLE nx_user_preference ADD COLUMN notify_genesis TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_preference' AND COLUMN_NAME='notify_system')=0,
  'ALTER TABLE nx_user_preference ADD COLUMN notify_system TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
