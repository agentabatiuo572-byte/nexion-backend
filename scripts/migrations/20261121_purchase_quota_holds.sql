-- Preserve legacy intents as SQL NULL. New reservations are opt-in at runtime.
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_hardware_quota_usage' AND COLUMN_NAME='purchase_intent_no'),
 'SELECT 1','ALTER TABLE nx_team_hardware_quota_usage ADD COLUMN purchase_intent_no VARCHAR(48) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_hardware_quota_usage' AND INDEX_NAME='uk_hardware_usage_intent_tier'),
 'SELECT 1','ALTER TABLE nx_team_hardware_quota_usage ADD UNIQUE KEY uk_hardware_usage_intent_tier(purchase_intent_no,quota_tier_id)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_purchase_payment_intent' AND COLUMN_NAME='quota_reservation_json'),
 'SELECT 1','ALTER TABLE nx_purchase_payment_intent ADD COLUMN quota_reservation_json JSON NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_purchase_e1_hold (
  intent_no VARCHAR(48) NOT NULL,
  line_no INT NOT NULL,
  product_no VARCHAR(64) NOT NULL,
  sku_id VARCHAR(64) NOT NULL,
  gate_generation BIGINT NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (intent_no,line_no),
  UNIQUE KEY uk_purchase_e1_hold_intent_sku (intent_no,sku_id),
  KEY idx_purchase_e1_hold_sku_generation (sku_id,gate_generation,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
