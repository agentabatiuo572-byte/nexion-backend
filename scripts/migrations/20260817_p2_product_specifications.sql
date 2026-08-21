-- P2: server-owned commercial product specifications edited by E1 and projected to App.
-- The startup runner executes before mapper initializers, so fresh databases
-- must not depend on DeviceCatalogMapper.createSkuTable().
CREATE TABLE IF NOT EXISTS nx_admin_device_sku (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sku_id VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  tier VARCHAR(32) NULL,
  tagline VARCHAR(255) NULL,
  badge VARCHAR(64) NULL,
  gpu VARCHAR(128) NULL,
  vram VARCHAR(64) NULL,
  hash_rate VARCHAR(64) NULL,
  power_text VARCHAR(64) NULL,
  datacenter VARCHAR(128) NULL,
  uptime VARCHAR(64) NULL,
  warranty VARCHAR(128) NULL,
  phone_daily_earn DECIMAL(18,6) NULL,
  phone_daily_earn_nex DECIMAL(18,6) NULL,
  price DECIMAL(18,4) NOT NULL DEFAULT 0,
  daily_earn DECIMAL(18,4) NOT NULL DEFAULT 0,
  daily_earn_nex DECIMAL(18,4) NOT NULL DEFAULT 0,
  share_yield_min DECIMAL(9,4) NULL,
  share_yield_max DECIMAL(9,4) NULL,
  base_rate VARCHAR(128) NULL,
  sold BIGINT NULL,
  stock_text VARCHAR(32) NOT NULL DEFAULT '0',
  rating DECIMAL(4,2) NULL,
  reviews BIGINT NULL,
  ai_image_gen_per_min BIGINT NULL,
  ai_llm_tokens_per_sec BIGINT NULL,
  ai_video_min_per_hour BIGINT NULL,
  ai_fine_tune_mins BIGINT NULL,
  ai_unlocks VARCHAR(255) NULL,
  features_json TEXT NULL,
  generation INT NULL,
  lifecycle VARCHAR(32) NULL,
  superseded_by VARCHAR(64) NULL,
  tradein_discount DECIMAL(18,4) NULL,
  unlock_phase VARCHAR(32) NOT NULL DEFAULT '',
  unlock_phase_id BIGINT NULL,
  purchase_gate_json TEXT NULL,
  image_asset_id VARCHAR(512) NULL,
  image_object_key VARCHAR(255) NULL,
  image_preview_url TEXT NULL,
  tag VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_device_sku (sku_id),
  KEY idx_admin_device_sku_status (status,is_deleted),
  KEY idx_admin_device_sku_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_device_sku' AND COLUMN_NAME='uptime')=0,
  'ALTER TABLE nx_admin_device_sku ADD COLUMN uptime VARCHAR(64) NULL AFTER datacenter', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Specifications are copied into each acceptance RunID catalog. Reads must not
-- join live E1 metadata, otherwise an operator edit mutates an existing run.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='power_text')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN power_text VARCHAR(64) NULL AFTER unlock_phase', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='datacenter')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN datacenter VARCHAR(128) NULL AFTER power_text', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='uptime')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN uptime VARCHAR(64) NULL AFTER datacenter', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='warranty')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN warranty VARCHAR(128) NULL AFTER uptime', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='phone_daily_earn')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN phone_daily_earn DECIMAL(18,6) NULL AFTER warranty', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='phone_daily_earn_nex')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN phone_daily_earn_nex DECIMAL(18,6) NULL AFTER phone_daily_earn', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='features_json')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN features_json TEXT NULL AFTER phone_daily_earn_nex', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='ai_image_gen_per_min')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN ai_image_gen_per_min DECIMAL(18,6) NULL AFTER features_json', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='ai_llm_tokens_per_sec')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN ai_llm_tokens_per_sec DECIMAL(18,6) NULL AFTER ai_image_gen_per_min', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='ai_video_min_per_hour')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN ai_video_min_per_hour DECIMAL(18,6) NULL AFTER ai_llm_tokens_per_sec', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='ai_fine_tune_mins')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN ai_fine_tune_mins DECIMAL(18,6) NULL AFTER ai_video_min_per_hour', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='ai_unlocks')=0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN ai_unlocks VARCHAR(255) NULL AFTER ai_fine_tune_mins', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_device_sku' AND COLUMN_NAME='warranty')=0,
  'ALTER TABLE nx_admin_device_sku ADD COLUMN warranty VARCHAR(128) NULL AFTER uptime', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_device_sku' AND COLUMN_NAME='phone_daily_earn')=0,
  'ALTER TABLE nx_admin_device_sku ADD COLUMN phone_daily_earn DECIMAL(18,6) NULL AFTER warranty', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_device_sku' AND COLUMN_NAME='phone_daily_earn_nex')=0,
  'ALTER TABLE nx_admin_device_sku ADD COLUMN phone_daily_earn_nex DECIMAL(18,6) NULL AFTER phone_daily_earn', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
