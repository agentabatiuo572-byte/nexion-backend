-- Read-path index only. No event, receipt, notification or wallet rows are changed.
-- Preserve COALESCE(event_ts, created_at), including its millisecond precision.
-- Rollback after reverting the reader:
-- ALTER TABLE nx_event_outbox DROP INDEX idx_outbox_nova_candidate, ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE nx_event_outbox DROP COLUMN nova_business_occurred_at, ALGORITHM=INPLACE, LOCK=NONE;
-- MySQL requires the VIRTUAL column and index to be added in separate online steps.
SET @nova_candidate_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox'
       AND column_name = 'nova_business_occurred_at'
);
SET @nova_candidate_sql = IF(@nova_candidate_column_exists = 0,
    'ALTER TABLE nx_event_outbox ADD COLUMN nova_business_occurred_at DATETIME(3) GENERATED ALWAYS AS (COALESCE(event_ts, created_at)) VIRTUAL, ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE nova_candidate_stmt FROM @nova_candidate_sql;
EXECUTE nova_candidate_stmt;
DEALLOCATE PREPARE nova_candidate_stmt;

SET @nova_candidate_column_valid = (
    SELECT COUNT(*) = 1 AND MIN(column_type = 'datetime(3)') = 1
       AND MIN(extra LIKE '%VIRTUAL GENERATED%') = 1 AND MIN(is_nullable = 'YES') = 1
       AND MIN(LOWER(REPLACE(REPLACE(generation_expression, CHAR(96), ''), ' ', ''))
           = 'coalesce(event_ts,created_at)') = 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox'
       AND column_name = 'nova_business_occurred_at'
);
SET @nova_candidate_sql = IF(@nova_candidate_column_valid = 1,
    'SELECT 1', 'FAIL NOVA_BUSINESS_CANDIDATE_COLUMN_SHAPE_INVALID');
PREPARE nova_candidate_stmt FROM @nova_candidate_sql;
EXECUTE nova_candidate_stmt;
DEALLOCATE PREPARE nova_candidate_stmt;

SET @nova_candidate_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox'
       AND index_name = 'idx_outbox_nova_candidate'
);
SET @nova_candidate_sql = IF(@nova_candidate_index_exists = 0,
    'ALTER TABLE nx_event_outbox ADD INDEX idx_outbox_nova_candidate (event_name, is_deleted, is_server_authoritative, nova_business_occurred_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE nova_candidate_stmt FROM @nova_candidate_sql;
EXECUTE nova_candidate_stmt;
DEALLOCATE PREPARE nova_candidate_stmt;

SET @nova_candidate_index_valid = (
    SELECT COUNT(*) = 5 AND MIN(non_unique) = 1 AND MAX(non_unique) = 1
       AND SUM(sub_part IS NOT NULL) = 0 AND SUM(is_visible != 'YES') = 0
       AND LOWER(REPLACE(REPLACE(GROUP_CONCAT(COALESCE(column_name, expression)
           ORDER BY seq_in_index SEPARATOR '|'), CHAR(96), ''), ' ', ''))
           = 'event_name|is_deleted|is_server_authoritative|nova_business_occurred_at|id'
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox'
       AND index_name = 'idx_outbox_nova_candidate'
);
SET @nova_candidate_sql = IF(@nova_candidate_index_valid = 1,
    'SELECT 1', 'FAIL NOVA_BUSINESS_CANDIDATE_INDEX_SHAPE_INVALID');
PREPARE nova_candidate_stmt FROM @nova_candidate_sql;
EXECUTE nova_candidate_stmt;
DEALLOCATE PREPARE nova_candidate_stmt;
