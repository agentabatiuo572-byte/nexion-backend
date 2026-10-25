-- Discovery-only deletion: retain lifecycle, contracts, orders, budgets and obligations.
SET @promotion_delete_sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_promotion' AND COLUMN_NAME='deleted_at')=0,
  'ALTER TABLE nx_promotion ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL', 'SELECT 1');
PREPARE promotion_delete_stmt FROM @promotion_delete_sql;
EXECUTE promotion_delete_stmt;
DEALLOCATE PREPARE promotion_delete_stmt;
SET @promotion_delete_sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_promotion' AND COLUMN_NAME='deleted_by')=0,
  'ALTER TABLE nx_promotion ADD COLUMN deleted_by BIGINT NULL DEFAULT NULL', 'SELECT 1');
PREPARE promotion_delete_stmt FROM @promotion_delete_sql;
EXECUTE promotion_delete_stmt;
DEALLOCATE PREPARE promotion_delete_stmt;
