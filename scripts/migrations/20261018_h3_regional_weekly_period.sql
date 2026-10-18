-- Additive schema only. Activation and calling-code mappings require the normal H3/A2 policy flow.
-- Existing WEEK: keys, bounds, progress, rewards and claims are not changed.
SET NAMES utf8mb4;
SET @regional_week_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_growth_weekly_definition' AND COLUMN_NAME='business_timezone')=0,
  'ALTER TABLE nx_growth_weekly_definition ADD COLUMN business_timezone VARCHAR(64) NULL', 'SELECT 1');
PREPARE regional_week_stmt FROM @regional_week_sql;
EXECUTE regional_week_stmt;
DEALLOCATE PREPARE regional_week_stmt;
SET @regional_week_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_growth_weekly_definition' AND COLUMN_NAME='local_week_start')=0,
  'ALTER TABLE nx_growth_weekly_definition ADD COLUMN local_week_start DATE NULL', 'SELECT 1');
PREPARE regional_week_stmt FROM @regional_week_sql;
EXECUTE regional_week_stmt;
DEALLOCATE PREPARE regional_week_stmt;
SET @regional_week_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_growth_weekly_definition' AND COLUMN_NAME='calendar_version')=0,
  'ALTER TABLE nx_growth_weekly_definition ADD COLUMN calendar_version VARCHAR(32) NULL', 'SELECT 1');
PREPARE regional_week_stmt FROM @regional_week_sql;
EXECUTE regional_week_stmt;
DEALLOCATE PREPARE regional_week_stmt;
SET @regional_week_sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_growth_weekly_definition' AND INDEX_NAME='uk_weekly_region_window')=0,
  'ALTER TABLE nx_growth_weekly_definition ADD UNIQUE KEY uk_weekly_region_window(calendar_version,business_timezone,local_week_start,eligible_from,eligible_until)', 'SELECT 1');
PREPARE regional_week_stmt FROM @regional_week_sql;
EXECUTE regional_week_stmt;
DEALLOCATE PREPARE regional_week_stmt;

CREATE TABLE IF NOT EXISTS nx_growth_account_weekly_period (
  user_id BIGINT NOT NULL,
  local_week_start DATE NOT NULL,
  instance_key VARCHAR(48) NOT NULL,
  country_calling_code VARCHAR(8) NOT NULL,
  mapping_version BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id,local_week_start),
  UNIQUE KEY uk_account_weekly_instance(user_id,instance_key),
  CONSTRAINT fk_account_weekly_definition FOREIGN KEY(instance_key) REFERENCES nx_growth_weekly_definition(instance_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
