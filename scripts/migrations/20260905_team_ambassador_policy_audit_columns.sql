-- The first F4 deployment created nx_team_ambassador_policy without the two
-- audit columns later consumed by TeamCommissionMapper. Repair upgraded
-- databases without overwriting operator-owned policy rows.
SET @f4_updated_by_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_team_ambassador_policy'
     AND COLUMN_NAME = 'updated_by'
);
SET @f4_updated_by_sql := IF(
  @f4_updated_by_exists = 0,
  'ALTER TABLE nx_team_ambassador_policy ADD COLUMN updated_by VARCHAR(64) NULL AFTER revision',
  'SELECT 1'
);
PREPARE f4_updated_by_stmt FROM @f4_updated_by_sql;
EXECUTE f4_updated_by_stmt;
DEALLOCATE PREPARE f4_updated_by_stmt;

SET @f4_updated_at_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_team_ambassador_policy'
     AND COLUMN_NAME = 'updated_at'
);
SET @f4_updated_at_sql := IF(
  @f4_updated_at_exists = 0,
  'ALTER TABLE nx_team_ambassador_policy ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)',
  'SELECT 1'
);
PREPARE f4_updated_at_stmt FROM @f4_updated_at_sql;
EXECUTE f4_updated_at_stmt;
DEALLOCATE PREPARE f4_updated_at_stmt;
