-- Keep an immutable lineage for every live lifetime-quota reservation.  A PC
-- gate edit/reset increments the SKU generation, so an old cancellation can
-- restore stock without decrementing the newly configured quota.
SET @sku_quota_generation_exists := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_admin_device_sku'
     AND COLUMN_NAME = 'purchase_gate_generation'
);
SET @sku_quota_generation_sql := IF(
  @sku_quota_generation_exists = 0,
  'ALTER TABLE nx_admin_device_sku ADD COLUMN purchase_gate_generation BIGINT NOT NULL DEFAULT 1 AFTER purchase_gate_json',
  'SELECT 1'
);
PREPARE sku_quota_generation_stmt FROM @sku_quota_generation_sql;
EXECUTE sku_quota_generation_stmt;
DEALLOCATE PREPARE sku_quota_generation_stmt;

SET @order_quota_generation_exists := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_order_item'
     AND COLUMN_NAME = 'lifetime_quota_gate_generation'
);
SET @order_quota_generation_sql := IF(
  @order_quota_generation_exists = 0,
  'ALTER TABLE nx_order_item ADD COLUMN lifetime_quota_gate_generation BIGINT NULL AFTER lifetime_quota_reserved',
  'SELECT 1'
);
PREPARE order_quota_generation_stmt FROM @order_quota_generation_sql;
EXECUTE order_quota_generation_stmt;
DEALLOCATE PREPARE order_quota_generation_stmt;
