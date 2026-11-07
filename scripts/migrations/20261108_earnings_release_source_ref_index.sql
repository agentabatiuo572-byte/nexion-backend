-- Preserve the complete source_ref lookup, including contradictory rows from any source/user.
-- Non-unique full-column index only; no business rows or existing indexes are changed.
-- Rollback: ALTER TABLE nx_earnings_release_entry DROP INDEX idx_earnings_release_source_ref, ALGORITHM=INPLACE, LOCK=NONE;
SET @earnings_source_ref_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_earnings_release_entry'
       AND index_name = 'idx_earnings_release_source_ref'
);
SET @earnings_source_ref_sql = IF(@earnings_source_ref_index_exists = 0,
    'ALTER TABLE nx_earnings_release_entry ADD INDEX idx_earnings_release_source_ref (source_ref), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE earnings_source_ref_stmt FROM @earnings_source_ref_sql;
EXECUTE earnings_source_ref_stmt;
DEALLOCATE PREPARE earnings_source_ref_stmt;

SET @earnings_source_ref_index_valid = (
    SELECT COUNT(*) = 1 AND MIN(non_unique) = 1 AND MAX(non_unique) = 1
       AND SUM(sub_part IS NOT NULL) = 0 AND SUM(is_visible != 'YES') = 0
       AND SUM(index_type != 'BTREE') = 0 AND SUM(collation != 'A') = 0
       AND LOWER(REPLACE(REPLACE(GROUP_CONCAT(COALESCE(column_name, expression)
           ORDER BY seq_in_index SEPARATOR '|'), CHAR(96), ''), ' ', '')) = 'source_ref'
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_earnings_release_entry'
       AND index_name = 'idx_earnings_release_source_ref'
);
SET @earnings_source_ref_sql = IF(@earnings_source_ref_index_valid = 1,
    'SELECT 1', 'FAIL EARNINGS_RELEASE_SOURCE_REF_INDEX_SHAPE_INVALID');
PREPARE earnings_source_ref_stmt FROM @earnings_source_ref_sql;
EXECUTE earnings_source_ref_stmt;
DEALLOCATE PREPARE earnings_source_ref_stmt;
