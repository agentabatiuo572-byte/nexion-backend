-- Cover the original source_ref/source_type history lookup across every environment and run.
-- Non-unique full-column index only; no business rows or existing indexes are changed.
-- Rollback: ALTER TABLE nx_direct_referral_settlement DROP INDEX idx_direct_source_ref_type, ALGORITHM=INPLACE, LOCK=NONE;
SET @direct_source_ref_type_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_direct_referral_settlement'
       AND index_name = 'idx_direct_source_ref_type'
);
SET @direct_source_ref_type_sql = IF(@direct_source_ref_type_index_exists = 0,
    'ALTER TABLE nx_direct_referral_settlement ADD INDEX idx_direct_source_ref_type (source_ref, source_type), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE direct_source_ref_type_stmt FROM @direct_source_ref_type_sql;
EXECUTE direct_source_ref_type_stmt;
DEALLOCATE PREPARE direct_source_ref_type_stmt;

SET @direct_source_ref_type_index_valid = (
    SELECT COUNT(*) = 2 AND MIN(non_unique) = 1 AND MAX(non_unique) = 1
       AND SUM(sub_part IS NOT NULL) = 0 AND SUM(is_visible != 'YES') = 0
       AND SUM(index_type != 'BTREE') = 0 AND SUM(collation != 'A') = 0
       AND LOWER(REPLACE(REPLACE(GROUP_CONCAT(COALESCE(column_name, expression)
           ORDER BY seq_in_index SEPARATOR '|'), CHAR(96), ''), ' ', '')) = 'source_ref|source_type'
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_direct_referral_settlement'
       AND index_name = 'idx_direct_source_ref_type'
);
SET @direct_source_ref_type_sql = IF(@direct_source_ref_type_index_valid = 1,
    'SELECT 1', 'FAIL DIRECT_SOURCE_REF_TYPE_INDEX_SHAPE_INVALID');
PREPARE direct_source_ref_type_stmt FROM @direct_source_ref_type_sql;
EXECUTE direct_source_ref_type_stmt;
DEALLOCATE PREPARE direct_source_ref_type_stmt;
