-- H7 popup cadence is server-owned.  The popup state is account-scoped so a
-- relogin or second device cannot bypass the cooldown.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_voucher' AND COLUMN_NAME='popup_delay_ms')=0,
  'ALTER TABLE nx_growth_voucher ADD COLUMN popup_delay_ms BIGINT NOT NULL DEFAULT 1300 AFTER popup_enabled','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_voucher' AND COLUMN_NAME='popup_cooldown_hours')=0,
  'ALTER TABLE nx_growth_voucher ADD COLUMN popup_cooldown_hours BIGINT NOT NULL DEFAULT 24 AFTER popup_delay_ms','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_voucher' AND COLUMN_NAME='popup_max_per_session')=0,
  'ALTER TABLE nx_growth_voucher ADD COLUMN popup_max_per_session BIGINT NOT NULL DEFAULT 1 AFTER popup_cooldown_hours','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_voucher' AND COLUMN_NAME='popup_cadence_enabled')=0,
  'ALTER TABLE nx_growth_voucher ADD COLUMN popup_cadence_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER popup_max_per_session','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_voucher_popup_state (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  voucher_id VARCHAR(80) NOT NULL,
  last_seen_at BIGINT NOT NULL DEFAULT 0,
  session_count BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_nx_voucher_popup_user_voucher (user_id, voucher_id),
  KEY idx_nx_voucher_popup_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
