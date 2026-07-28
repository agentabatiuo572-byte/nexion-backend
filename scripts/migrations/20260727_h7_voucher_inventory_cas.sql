-- H7 voucher inventory, optimistic concurrency and durable grant lifecycle.
SET @h7_limit_exists := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_growth_voucher'
     AND COLUMN_NAME = 'issuance_limit'
);
SET @h7_limit_sql := IF(
  @h7_limit_exists = 0,
  'ALTER TABLE nx_growth_voucher ADD COLUMN issuance_limit BIGINT NOT NULL DEFAULT 0 AFTER splittable',
  'SELECT 1'
);
PREPARE h7_limit_stmt FROM @h7_limit_sql;
EXECUTE h7_limit_stmt;
DEALLOCATE PREPARE h7_limit_stmt;

SET @h7_version_exists := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_growth_voucher'
     AND COLUMN_NAME = 'version'
);
SET @h7_version_sql := IF(
  @h7_version_exists = 0,
  'ALTER TABLE nx_growth_voucher ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER issuance_limit',
  'SELECT 1'
);
PREPARE h7_version_stmt FROM @h7_version_sql;
EXECUTE h7_version_stmt;
DEALLOCATE PREPARE h7_version_stmt;
