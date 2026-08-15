-- Controlled migration for acceptance-only commerce settlement facts.
-- The controlled startup runner applies it before an acceptance runtime starts.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_user' AND COLUMN_NAME='sandbox')=0,
  'ALTER TABLE nx_user ADD COLUMN sandbox TINYINT NOT NULL DEFAULT 0 AFTER status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Isolated, one-way catalogue snapshot. Checkout reserves only this stock,
-- never nx_product stock, and each order copies the locked price/quantity.
CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_catalog (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  product_no VARCHAR(96) NOT NULL,
  name VARCHAR(255) NOT NULL,
  tier VARCHAR(32) NOT NULL,
  price_usdt DECIMAL(18,6) NOT NULL,
  stock INT NOT NULL,
  sold_count INT NOT NULL DEFAULT 0,
  device_type VARCHAR(64) NULL,
  generation VARCHAR(64) NULL,
  gpu_model VARCHAR(255) NULL,
  vram_total_gb INT NULL,
  hashrate DECIMAL(18,6) NULL,
  daily_usdt DECIMAL(18,6) NOT NULL,
  daily_nex DECIMAL(18,6) NOT NULL,
  tagline VARCHAR(255) NULL,
  badge VARCHAR(128) NULL,
  unlock_phase VARCHAR(32) NULL,
  purchase_gate_json TEXT NULL,
  run_id VARCHAR(96) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_commerce_sandbox_catalog_run_product (run_id,product_id),
  UNIQUE KEY uk_commerce_sandbox_catalog_run_no (run_id,product_no),
  CONSTRAINT chk_commerce_sandbox_catalog_stock CHECK (stock >= 0 AND sold_count >= 0),
  CONSTRAINT chk_commerce_sandbox_catalog_price CHECK (price_usdt > 0),
  CONSTRAINT chk_commerce_sandbox_catalog_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
             AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='purchase_gate_json')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN purchase_gate_json TEXT NULL AFTER unlock_phase','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  order_type VARCHAR(16) NOT NULL DEFAULT 'SINGLE',
  item_count INT NOT NULL DEFAULT 1,
  amount_usdt DECIMAL(18,6) NOT NULL,
  canonical_revision BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  state VARCHAR(32) NOT NULL,
  wallet_debited TINYINT NOT NULL DEFAULT 0,
  stock_returned TINYINT NOT NULL DEFAULT 0,
  run_id VARCHAR(96) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_commerce_sandbox_order_run_no (run_id,order_no),
  KEY idx_commerce_sandbox_order_user_time (run_id,user_id,created_at),
  CONSTRAINT chk_commerce_sandbox_order_quantity CHECK (quantity > 0),
  CONSTRAINT chk_commerce_sandbox_order_amount CHECK (amount_usdt >= 0),
  CONSTRAINT chk_commerce_sandbox_order_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_inventory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(96) NOT NULL,
  product_id BIGINT NOT NULL,
  product_no VARCHAR(96) NOT NULL,
  unit_price_usdt DECIMAL(18,6) NOT NULL,
  reserved_quantity INT NOT NULL,
  released_quantity INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  run_id VARCHAR(96) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_commerce_sandbox_inventory_run_order_product (run_id,order_no,product_id),
  CONSTRAINT chk_commerce_sandbox_inventory_quantity CHECK (reserved_quantity > 0 AND released_quantity IN (0,reserved_quantity)),
  CONSTRAINT chk_commerce_sandbox_inventory_price CHECK (unit_price_usdt > 0),
  CONSTRAINT chk_commerce_sandbox_inventory_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_callback_inbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(128) NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  target_status VARCHAR(48) NOT NULL,
  expected_version BIGINT NOT NULL,
  request_hash CHAR(64) NOT NULL,
  canonical_status VARCHAR(48) NOT NULL,
  result_version BIGINT NOT NULL,
  wallet_after DECIMAL(18,6) NULL,
  run_id VARCHAR(96) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  received_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_commerce_sandbox_callback_run_event (run_id,event_id),
  KEY idx_commerce_sandbox_callback_order (run_id,order_no,received_at),
  CONSTRAINT chk_commerce_sandbox_callback_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_order_receipt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  result_json JSON NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_commerce_sandbox_receipt_run_user_key (run_id,user_id,idempotency_key),
  CONSTRAINT chk_commerce_sandbox_receipt_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_commerce_sandbox_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id VARCHAR(96) NOT NULL, event_id VARCHAR(128) NOT NULL,
  order_no VARCHAR(96) NOT NULL, actor VARCHAR(128) NOT NULL, reason VARCHAR(300) NOT NULL, event VARCHAR(48) NOT NULL,
  replay TINYINT NOT NULL, canonical_status VARCHAR(48) NOT NULL, result_version BIGINT NOT NULL, wallet_after DECIMAL(18,6) NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock', source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  strict_profile TINYINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_commerce_sandbox_audit_run_order (run_id,order_no,created_at),
  CONSTRAINT chk_commerce_sandbox_audit_source CHECK (source='mock' AND source_environment='SANDBOX' AND strict_profile=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_commerce_sandbox_callback_inbox' AND COLUMN_NAME='canonical_status')=0,
  'ALTER TABLE nx_commerce_sandbox_callback_inbox ADD COLUMN canonical_status VARCHAR(48) NOT NULL DEFAULT ''placed'' AFTER request_hash', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_commerce_sandbox_callback_inbox' AND COLUMN_NAME='result_version')=0,
  'ALTER TABLE nx_commerce_sandbox_callback_inbox ADD COLUMN result_version BIGINT NOT NULL DEFAULT 0 AFTER canonical_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_commerce_sandbox_callback_inbox' AND COLUMN_NAME='wallet_after')=0,
  'ALTER TABLE nx_commerce_sandbox_callback_inbox ADD COLUMN wallet_after DECIMAL(18,6) NULL AFTER result_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Forward v1 -> run-scoped facts. This block deliberately follows every CREATE.
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='run_id')=0,'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN run_id VARCHAR(96) NOT NULL DEFAULT '''' AFTER unlock_phase','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_order' AND COLUMN_NAME='run_id')=0,'ALTER TABLE nx_commerce_sandbox_order ADD COLUMN run_id VARCHAR(96) NOT NULL DEFAULT '''' AFTER stock_returned','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_order' AND COLUMN_NAME='order_type')=0,'ALTER TABLE nx_commerce_sandbox_order ADD COLUMN order_type VARCHAR(16) NOT NULL DEFAULT ''SINGLE'' AFTER quantity','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_order' AND COLUMN_NAME='item_count')=0,'ALTER TABLE nx_commerce_sandbox_order ADD COLUMN item_count INT NOT NULL DEFAULT 1 AFTER order_type','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_inventory' AND COLUMN_NAME='run_id')=0,'ALTER TABLE nx_commerce_sandbox_inventory ADD COLUMN run_id VARCHAR(96) NOT NULL DEFAULT '''' AFTER version','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_callback_inbox' AND COLUMN_NAME='run_id')=0,'ALTER TABLE nx_commerce_sandbox_callback_inbox ADD COLUMN run_id VARCHAR(96) NOT NULL DEFAULT '''' AFTER wallet_after','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND INDEX_NAME='uk_commerce_sandbox_catalog_product')>0,'ALTER TABLE nx_commerce_sandbox_catalog DROP INDEX uk_commerce_sandbox_catalog_product','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND INDEX_NAME='uk_commerce_sandbox_catalog_no')>0,'ALTER TABLE nx_commerce_sandbox_catalog DROP INDEX uk_commerce_sandbox_catalog_no','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_order' AND INDEX_NAME='uk_commerce_sandbox_order_no')>0,'ALTER TABLE nx_commerce_sandbox_order DROP INDEX uk_commerce_sandbox_order_no','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_inventory' AND INDEX_NAME='uk_commerce_sandbox_inventory_order')>0,'ALTER TABLE nx_commerce_sandbox_inventory DROP INDEX uk_commerce_sandbox_inventory_order','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_inventory' AND INDEX_NAME='uk_commerce_sandbox_inventory_run_order')>0,'ALTER TABLE nx_commerce_sandbox_inventory DROP INDEX uk_commerce_sandbox_inventory_run_order','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_callback_inbox' AND INDEX_NAME='uk_commerce_sandbox_callback_event')>0,'ALTER TABLE nx_commerce_sandbox_callback_inbox DROP INDEX uk_commerce_sandbox_callback_event','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND INDEX_NAME='uk_commerce_sandbox_catalog_run_product')=0,'ALTER TABLE nx_commerce_sandbox_catalog ADD UNIQUE KEY uk_commerce_sandbox_catalog_run_product (run_id,product_id)','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND INDEX_NAME='uk_commerce_sandbox_catalog_run_no')=0,'ALTER TABLE nx_commerce_sandbox_catalog ADD UNIQUE KEY uk_commerce_sandbox_catalog_run_no (run_id,product_no)','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_order' AND INDEX_NAME='uk_commerce_sandbox_order_run_no')=0,'ALTER TABLE nx_commerce_sandbox_order ADD UNIQUE KEY uk_commerce_sandbox_order_run_no (run_id,order_no)','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_inventory' AND INDEX_NAME='uk_commerce_sandbox_inventory_run_order_product')=0,'ALTER TABLE nx_commerce_sandbox_inventory ADD UNIQUE KEY uk_commerce_sandbox_inventory_run_order_product (run_id,order_no,product_id)','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_callback_inbox' AND INDEX_NAME='uk_commerce_sandbox_callback_run_event')=0,'ALTER TABLE nx_commerce_sandbox_callback_inbox ADD UNIQUE KEY uk_commerce_sandbox_callback_run_event (run_id,event_id)','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
