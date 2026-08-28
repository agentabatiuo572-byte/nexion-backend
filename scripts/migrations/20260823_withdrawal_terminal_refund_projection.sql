-- Make the App withdrawal projection executable on upgraded databases. These
-- durable terminal/refund facts already belong to the canonical business
-- table in scripts/schema.sql; this rerunnable upgrade closes schema drift.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
                 AND COLUMN_NAME='terminal_reason') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN terminal_reason VARCHAR(64) NULL AFTER failure_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
                 AND COLUMN_NAME='retriable') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN retriable TINYINT(1) NULL AFTER terminal_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
                 AND COLUMN_NAME='nex_refunded') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN nex_refunded DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER retriable',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
                 AND COLUMN_NAME='nex_refunded_at') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN nex_refunded_at DATETIME NULL AFTER nex_refunded',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
