-- Forward schema repair for withdrawal list, detail and submission.
-- Keep existing columns and values unchanged; do not replay historic event or risk backfills.
-- New columns retain the original schema defaults; no historic amounts are recalculated.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_network_fee_rate')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_network_fee_rate DECIMAL(10,6) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_network_fee_min')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_network_fee_min DECIMAL(18,6) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_network_fee_max')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_network_fee_max DECIMAL(18,6) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_network_fee')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_network_fee DECIMAL(18,6) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_routing_priority')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_routing_priority VARCHAR(16) NOT NULL DEFAULT ''NORMAL''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k3_risk_route')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k3_risk_route VARCHAR(16) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_risk_score')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_risk_score INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_model_version')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_model_version VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_as_of')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_as_of DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_band_low_max')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_band_low_max INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_band_high_min')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_band_high_min INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_auto_escalate_score')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_auto_escalate_score INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
