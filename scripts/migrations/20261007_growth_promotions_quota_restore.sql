-- Restore eligibility counts only after a fully settled whole-order refund.
-- Nullable additive marker: historical receipts remain intact; no approval or usage is seeded.
SET @growth_promotion_quota_restore_sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_promotion_order_receipt' AND COLUMN_NAME='quota_restored_at')=0,
  'ALTER TABLE nx_promotion_order_receipt ADD COLUMN quota_restored_at DATETIME(6) NULL DEFAULT NULL',
  'SELECT 1');
PREPARE growth_promotion_quota_restore_stmt FROM @growth_promotion_quota_restore_sql;
EXECUTE growth_promotion_quota_restore_stmt;
DEALLOCATE PREPARE growth_promotion_quota_restore_stmt;
