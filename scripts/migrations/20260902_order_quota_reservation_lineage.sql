-- Record whether each order line actually consumed the lifetime purchase quota.
-- Existing rows intentionally default to 0: inferring history from today's SKU policy is unsafe.
SET @quota_lineage_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_order_item'
     AND COLUMN_NAME = 'lifetime_quota_reserved'
);
SET @quota_lineage_sql := IF(
  @quota_lineage_column_exists = 0,
  'ALTER TABLE nx_order_item ADD COLUMN lifetime_quota_reserved TINYINT NOT NULL DEFAULT 0 AFTER line_amount_usdt',
  'SELECT 1'
);
PREPARE quota_lineage_stmt FROM @quota_lineage_sql;
EXECUTE quota_lineage_stmt;
DEALLOCATE PREPARE quota_lineage_stmt;
