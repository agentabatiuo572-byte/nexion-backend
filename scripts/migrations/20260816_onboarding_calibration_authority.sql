-- Server-authoritative phone onboarding calibration and estimate projection.
-- Raw device observations are retained for traceability; final capability and
-- rates are derived from the versioned rows below, never from client claims.
CREATE TABLE IF NOT EXISTS nx_onboarding_phone_tier_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tier INT NOT NULL,
  name VARCHAR(64) NOT NULL,
  tops_min INT NOT NULL,
  tops_max INT NOT NULL,
  base_rate_usdt DECIMAL(18,6) NOT NULL,
  base_rate_nex DECIMAL(18,6) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  active TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_onboarding_phone_tier (tier),
  KEY idx_onboarding_phone_tier_active (active,is_deleted,tier),
  CONSTRAINT chk_onboarding_phone_tier_range CHECK (tier BETWEEN 1 AND 5 AND tops_min >= 1 AND tops_max >= tops_min),
  CONSTRAINT chk_onboarding_phone_tier_rate CHECK (base_rate_usdt > 0 AND base_rate_nex > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_onboarding_phone_tier_config
  (tier,name,tops_min,tops_max,base_rate_usdt,base_rate_nex,revision,active,is_deleted)
VALUES
  (1,'Tier 1',8,18,0.040000,6.000000,1,1,0),
  (2,'Tier 2',19,24,0.050000,8.000000,1,1,0),
  (3,'Tier 3',25,34,0.060000,10.000000,1,1,0),
  (4,'Tier 4',35,46,0.080000,13.000000,1,1,0),
  (5,'Tier 5',47,58,0.095000,16.000000,1,1,0)
ON DUPLICATE KEY UPDATE tier=tier;

CREATE TABLE IF NOT EXISTS nx_onboarding_yield_comparison_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key VARCHAR(64) NOT NULL,
  label VARCHAR(128) NOT NULL,
  daily_usdt DECIMAL(18,6) NOT NULL,
  daily_nex DECIMAL(18,6) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 1,
  active TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_onboarding_yield_comparison_key (config_key),
  KEY idx_onboarding_yield_comparison_active (active,is_deleted,sort_order),
  CONSTRAINT chk_onboarding_yield_comparison_rate CHECK (daily_usdt > 0 AND daily_nex > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @onboarding_add_yield_rate_check = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_yield_comparison_config'
    AND CONSTRAINT_NAME='chk_onboarding_yield_comparison_rate')=0,
  'ALTER TABLE nx_onboarding_yield_comparison_config ADD CONSTRAINT chk_onboarding_yield_comparison_rate CHECK (daily_usdt > 0 AND daily_nex > 0)',
  'SELECT 1');
PREPARE onboarding_add_yield_rate_check_stmt FROM @onboarding_add_yield_rate_check;
EXECUTE onboarding_add_yield_rate_check_stmt;
DEALLOCATE PREPARE onboarding_add_yield_rate_check_stmt;

INSERT INTO nx_onboarding_yield_comparison_config
  (config_key,label,daily_usdt,daily_nex,sort_order,revision,active,is_deleted)
VALUES
  ('phone','手机',0.060000,10.000000,1,1,1,0),
  ('s1','NexionBox S1',1.200000,65.000000,2,1,1,0),
  ('pro','NexionBox Pro',4.800000,260.000000,3,1,1,0),
  ('rack','StellarRack',18.000000,980.000000,4,1,1,0)
ON DUPLICATE KEY UPDATE config_key=config_key;

CREATE TABLE IF NOT EXISTS nx_onboarding_calibration (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  signal_json JSON NOT NULL,
  derived_json JSON NOT NULL,
  comparison_json JSON NOT NULL,
  source VARCHAR(32) NOT NULL DEFAULT 'server',
  server_canonical TINYINT NOT NULL DEFAULT 1,
  source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  run_id VARCHAR(96) NOT NULL DEFAULT '',
  config_revision BIGINT NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_onboarding_calibration_scope (user_id,source_environment,run_id,device_id),
  UNIQUE KEY uk_onboarding_calibration_idem_scope (user_id,source_environment,run_id,device_id,idempotency_key),
  KEY idx_onboarding_calibration_user_scope (user_id,source_environment,run_id,updated_at),
  CONSTRAINT chk_onboarding_calibration_source CHECK (source='server' AND server_canonical=1),
  CONSTRAINT chk_onboarding_calibration_environment CHECK (
    source_environment IN ('PRODUCTION','SANDBOX')
    AND ((source_environment='PRODUCTION' AND run_id='') OR (source_environment='SANDBOX' AND run_id <> ''))
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Forward-only upgrade for databases created before the run-scoped fence.
SET @onboarding_add_source_environment = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND COLUMN_NAME='source_environment')=0,
  'ALTER TABLE nx_onboarding_calibration ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER server_canonical',
  'SELECT 1');
PREPARE onboarding_stmt FROM @onboarding_add_source_environment; EXECUTE onboarding_stmt; DEALLOCATE PREPARE onboarding_stmt;

SET @onboarding_add_run_id = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND COLUMN_NAME='run_id')=0,
  'ALTER TABLE nx_onboarding_calibration ADD COLUMN run_id VARCHAR(96) NOT NULL DEFAULT '''' AFTER source_environment',
  'SELECT 1');
PREPARE onboarding_stmt FROM @onboarding_add_run_id; EXECUTE onboarding_stmt; DEALLOCATE PREPARE onboarding_stmt;

UPDATE nx_onboarding_calibration
   SET source_environment='PRODUCTION', run_id=''
 WHERE source_environment IS NULL OR source_environment='' OR source_environment='production';

SET @onboarding_drop_old_device = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND INDEX_NAME='uk_onboarding_calibration_user_device')>0,
  'ALTER TABLE nx_onboarding_calibration DROP INDEX uk_onboarding_calibration_user_device',
  'SELECT 1');
PREPARE onboarding_stmt FROM @onboarding_drop_old_device; EXECUTE onboarding_stmt; DEALLOCATE PREPARE onboarding_stmt;

SET @onboarding_drop_old_key = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND INDEX_NAME='uk_onboarding_calibration_user_key')>0,
  'ALTER TABLE nx_onboarding_calibration DROP INDEX uk_onboarding_calibration_user_key',
  'SELECT 1');
PREPARE onboarding_stmt FROM @onboarding_drop_old_key; EXECUTE onboarding_stmt; DEALLOCATE PREPARE onboarding_stmt;

SET @onboarding_add_scope = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND INDEX_NAME='uk_onboarding_calibration_scope')=0,
  'ALTER TABLE nx_onboarding_calibration ADD UNIQUE KEY uk_onboarding_calibration_scope (user_id,source_environment,run_id,device_id)',
  'SELECT 1');
PREPARE onboarding_stmt FROM @onboarding_add_scope; EXECUTE onboarding_stmt; DEALLOCATE PREPARE onboarding_stmt;

SET @onboarding_add_idem_scope = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND INDEX_NAME='uk_onboarding_calibration_idem_scope')=0,
  'ALTER TABLE nx_onboarding_calibration ADD UNIQUE KEY uk_onboarding_calibration_idem_scope (user_id,source_environment,run_id,device_id,idempotency_key)',
  'SELECT 1');
PREPARE onboarding_stmt FROM @onboarding_add_idem_scope; EXECUTE onboarding_stmt; DEALLOCATE PREPARE onboarding_stmt;

SET @onboarding_add_scope_index = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND INDEX_NAME='idx_onboarding_calibration_user_scope')=0,
  'ALTER TABLE nx_onboarding_calibration ADD KEY idx_onboarding_calibration_user_scope (user_id,source_environment,run_id,updated_at)',
  'SELECT 1');
PREPARE onboarding_stmt FROM @onboarding_add_scope_index; EXECUTE onboarding_stmt; DEALLOCATE PREPARE onboarding_stmt;

SET @onboarding_add_environment_check = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND CONSTRAINT_NAME='chk_onboarding_calibration_environment')=0,
  'ALTER TABLE nx_onboarding_calibration ADD CONSTRAINT chk_onboarding_calibration_environment CHECK (source_environment IN (''PRODUCTION'',''SANDBOX'') AND ((source_environment=''PRODUCTION'' AND run_id='''') OR (source_environment=''SANDBOX'' AND run_id <> '''')))',
  'SELECT 1');
PREPARE onboarding_stmt FROM @onboarding_add_environment_check; EXECUTE onboarding_stmt; DEALLOCATE PREPARE onboarding_stmt;
