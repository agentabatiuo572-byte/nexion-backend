-- Cover only the ten original non-JSON receipt directory columns. No business rows are changed.
-- Existing indexes and the full original JSON reader remain unchanged.
-- Rollback: ALTER TABLE nx_support_fund_operation DROP INDEX idx_support_fund_operation_directory, ALGORITHM=INPLACE, LOCK=NONE;
SET @fund_directory_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_support_fund_operation'
       AND index_name = 'idx_support_fund_operation_directory'
);
SET @fund_directory_sql = IF(@fund_directory_index_exists = 0,
    'ALTER TABLE nx_support_fund_operation ADD INDEX idx_support_fund_operation_directory (id, op_key, business_type, phase, source_table, source_key, source_environment, receipt_sha256, protocol_version, recorded_db_utc), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE fund_directory_stmt FROM @fund_directory_sql;
EXECUTE fund_directory_stmt;
DEALLOCATE PREPARE fund_directory_stmt;

SET @fund_directory_index_valid = (
    SELECT COUNT(*) = 10 AND MIN(non_unique) = 1 AND MAX(non_unique) = 1
       AND SUM(sub_part IS NOT NULL) = 0 AND SUM(is_visible != 'YES') = 0
       AND SUM(index_type != 'BTREE') = 0 AND SUM(collation != 'A') = 0
       AND LOWER(REPLACE(REPLACE(GROUP_CONCAT(COALESCE(column_name, expression)
           ORDER BY seq_in_index SEPARATOR '|'), CHAR(96), ''), ' ', ''))
           = 'id|op_key|business_type|phase|source_table|source_key|source_environment|receipt_sha256|protocol_version|recorded_db_utc'
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_support_fund_operation'
       AND index_name = 'idx_support_fund_operation_directory'
);
SET @fund_directory_sql = IF(@fund_directory_index_valid = 1,
    'SELECT 1', 'FAIL SUPPORT_FUND_DIRECTORY_INDEX_SHAPE_INVALID');
PREPARE fund_directory_stmt FROM @fund_directory_sql;
EXECUTE fund_directory_stmt;
DEALLOCATE PREPARE fund_directory_stmt;
