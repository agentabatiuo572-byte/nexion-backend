-- Explicit inventory semantics for canonical store products. Never use a large
-- stock number as an infinity sentinel: UNLIMITED bypasses stock reservation
-- while sold_count continues to track net sold/reserved units.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product' AND COLUMN_NAME='inventory_mode') = 0,
  'ALTER TABLE nx_product ADD COLUMN inventory_mode VARCHAR(16) NOT NULL DEFAULT ''FINITE'' AFTER stock',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_product'
      AND CONSTRAINT_NAME='chk_product_unlimited_share' AND CONSTRAINT_TYPE='CHECK') = 0,
  'ALTER TABLE nx_product ADD CONSTRAINT chk_product_unlimited_share CHECK (inventory_mode = ''FINITE'' OR UPPER(product_type) = ''SHARE'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Acceptance snapshots preserve the same inventory semantics as nx_product.
-- They remain isolated data, but they must not turn an unlimited service into a
-- finite product merely because its canonical stock is stored as zero.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog' AND COLUMN_NAME='inventory_mode') = 0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN inventory_mode VARCHAR(16) NOT NULL DEFAULT ''FINITE'' AFTER stock',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog'
      AND CONSTRAINT_NAME='chk_commerce_sandbox_catalog_inventory_mode' AND CONSTRAINT_TYPE='CHECK') = 0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD CONSTRAINT chk_commerce_sandbox_catalog_inventory_mode CHECK (inventory_mode IN (''FINITE'',''UNLIMITED''))',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_commerce_sandbox_catalog'
      AND CONSTRAINT_NAME='chk_commerce_sandbox_catalog_unlimited_share' AND CONSTRAINT_TYPE='CHECK') = 0,
  'ALTER TABLE nx_commerce_sandbox_catalog ADD CONSTRAINT chk_commerce_sandbox_catalog_unlimited_share CHECK (inventory_mode = ''FINITE'' OR UPPER(device_type) = ''SHARE'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_product
   SET inventory_mode='UNLIMITED',
       updated_at=GREATEST(CURRENT_TIMESTAMP(6),updated_at + INTERVAL 1 MICROSECOND)
 WHERE product_no='cloud-share'
   AND UPPER(product_type)='SHARE'
   AND inventory_mode<>'UNLIMITED';

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_product'
      AND CONSTRAINT_NAME='chk_product_inventory_mode' AND CONSTRAINT_TYPE='CHECK') = 0,
  'ALTER TABLE nx_product ADD CONSTRAINT chk_product_inventory_mode CHECK (inventory_mode IN (''FINITE'',''UNLIMITED''))',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
