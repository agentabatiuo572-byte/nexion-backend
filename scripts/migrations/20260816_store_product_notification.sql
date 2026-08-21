-- App storefront "Notify me" subscriptions.  This is deliberately separate
-- from the historical product review/waitlist tables: it stores only the
-- account-scoped subscription and the server release snapshot shown to App.
CREATE TABLE IF NOT EXISTS nx_product_notification_subscription (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  product_no VARCHAR(64) NOT NULL,
  release_state VARCHAR(96) NOT NULL,
  release_phase_id VARCHAR(64) NULL,
  revision VARCHAR(64) NOT NULL,
  source VARCHAR(64) NOT NULL DEFAULT 'nx_product',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  run_id VARCHAR(96) NOT NULL DEFAULT '',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  active_subscription_key VARCHAR(256)
    GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' AND is_deleted = 0
                              THEN CONCAT(user_id, ':', source_environment, ':', run_id, ':', product_no) ELSE NULL END) STORED,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_product_notification_active (active_subscription_key),
  KEY idx_product_notification_user (user_id, source_environment, run_id, status, updated_at),
  KEY idx_product_notification_product (product_no, source_environment, run_id, status, updated_at),
  CONSTRAINT chk_product_notification_environment CHECK (source_environment IN ('PRODUCTION','SANDBOX')),
  CONSTRAINT chk_product_notification_run CHECK ((source_environment='PRODUCTION' AND run_id='') OR (source_environment='SANDBOX' AND run_id <> ''))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'nx_product_notification_subscription'
                 AND COLUMN_NAME = 'source_environment') = 0,
  'ALTER TABLE nx_product_notification_subscription ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER source',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'nx_product_notification_subscription'
                 AND COLUMN_NAME = 'run_id') = 0,
  'ALTER TABLE nx_product_notification_subscription ADD COLUMN run_id VARCHAR(96) NOT NULL DEFAULT '''' AFTER source_environment',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'nx_product_notification_subscription'
                 AND COLUMN_NAME = 'active_subscription_key') = 0,
  'ALTER TABLE nx_product_notification_subscription ADD COLUMN active_subscription_key VARCHAR(256) GENERATED ALWAYS AS (CASE WHEN status = ''ACTIVE'' AND is_deleted = 0 THEN CONCAT(user_id, '':'', source_environment, '':'', run_id, '':'', product_no) ELSE NULL END) STORED',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'nx_product_notification_subscription'
                 AND COLUMN_NAME = 'active_subscription_key'
                 AND EXTRA LIKE '%STORED GENERATED%'
                 AND GENERATION_EXPRESSION LIKE '%source_environment%'
                 AND GENERATION_EXPRESSION LIKE '%run_id%') = 1,
  'SELECT 1',
  'ALTER TABLE nx_product_notification_subscription MODIFY COLUMN active_subscription_key VARCHAR(256) GENERATED ALWAYS AS (CASE WHEN status = ''ACTIVE'' AND is_deleted = 0 THEN CONCAT(user_id, '':'', source_environment, '':'', run_id, '':'', product_no) ELSE NULL END) STORED');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product_notification_subscription'
                 AND INDEX_NAME='idx_product_notification_user_scope')=0,
  'ALTER TABLE nx_product_notification_subscription ADD KEY idx_product_notification_user_scope (user_id,source_environment,run_id,status,updated_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product_notification_subscription'
                 AND INDEX_NAME='idx_product_notification_product_scope')=0,
  'ALTER TABLE nx_product_notification_subscription ADD KEY idx_product_notification_product_scope (product_no,source_environment,run_id,status,updated_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_product_notification_subscription'
                 AND CONSTRAINT_NAME='chk_product_notification_environment')=0,
  'ALTER TABLE nx_product_notification_subscription ADD CONSTRAINT chk_product_notification_environment CHECK (source_environment IN (''PRODUCTION'',''SANDBOX''))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_product_notification_subscription'
                 AND CONSTRAINT_NAME='chk_product_notification_run')=0,
  'ALTER TABLE nx_product_notification_subscription ADD CONSTRAINT chk_product_notification_run CHECK ((source_environment=''PRODUCTION'' AND run_id='''') OR (source_environment=''SANDBOX'' AND run_id <> ''''))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'nx_product_notification_subscription'
                 AND INDEX_NAME = 'uk_product_notification_active') = 0,
  'ALTER TABLE nx_product_notification_subscription ADD UNIQUE KEY uk_product_notification_active (active_subscription_key)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
