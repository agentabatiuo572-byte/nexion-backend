-- Read-path indexes only: no task, event, wallet, or user rows are modified.
-- Add VIRTUAL columns separately from their indexes so every step supports INPLACE/LOCK=NONE.
-- Keep LOWER and each source column's exact charset/collation; no case-matching behavior changes.
-- Deploy before the canonical reader. Reverting the reader is safe with these indexes present.
-- Rollback after reverting the reader:
-- ALTER TABLE nx_compute_task DROP INDEX idx_task_development_count;
-- ALTER TABLE nx_event_outbox DROP INDEX idx_outbox_canonical_type;
-- ALTER TABLE nx_event_outbox DROP INDEX idx_outbox_canonical_name;
-- ALTER TABLE nx_event_outbox DROP COLUMN canonical_event_type, DROP COLUMN canonical_event_name;
SET @polling_read_lock_name = CONCAT('nx:polling-read:', LEFT(SHA2(DATABASE(), 256), 30));
SELECT GET_LOCK(@polling_read_lock_name, 10) INTO @polling_read_valid;
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_MIGRATION_LOCK_UNAVAILABLE');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SELECT character_set_name, collation_name
  INTO @polling_read_event_type_charset, @polling_read_event_type_collation
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'event_type';
SET @polling_read_valid = EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'event_type'
       AND column_type = 'varchar(96)' AND character_set_name IS NOT NULL AND collation_name IS NOT NULL
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_SOURCE_COLUMN_INVALID_event_type');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = (
    SELECT COUNT(*) = 0 OR (COUNT(*) = 1 AND COALESCE(SUM(column_type = 'varchar(96)' AND extra LIKE '%VIRTUAL GENERATED%'
       AND is_nullable = 'YES'
       AND LOWER(REPLACE(REPLACE(generation_expression, CHAR(96), ''), ' ', '')) = 'lower(event_type)'
       AND character_set_name = @polling_read_event_type_charset
       AND collation_name = @polling_read_event_type_collation), 0) = 1)
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'canonical_event_type'
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_COLUMN_SHAPE_INVALID_canonical_event_type');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SELECT character_set_name, collation_name
  INTO @polling_read_event_name_charset, @polling_read_event_name_collation
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'event_name';
SET @polling_read_valid = EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'event_name'
       AND column_type = 'varchar(128)' AND character_set_name IS NOT NULL AND collation_name IS NOT NULL
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_SOURCE_COLUMN_INVALID_event_name');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = (
    SELECT COUNT(*) = 0 OR (COUNT(*) = 1 AND COALESCE(SUM(column_type = 'varchar(128)' AND extra LIKE '%VIRTUAL GENERATED%'
       AND is_nullable = 'YES'
       AND LOWER(REPLACE(REPLACE(generation_expression, CHAR(96), ''), ' ', '')) = 'lower(event_name)'
       AND character_set_name = @polling_read_event_name_charset
       AND collation_name = @polling_read_event_name_collation), 0) = 1)
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'canonical_event_name'
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_COLUMN_SHAPE_INVALID_canonical_event_name');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

-- Check all same-name columns and keys before the first DDL; incompatible state fails closed.
SET @polling_read_valid = (
    SELECT COUNT(*) = 0 OR (COUNT(*) = 6 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND collation = 'A' AND expression IS NULL AND (
          (seq_in_index = 1 AND column_name = 'user_id')
          OR (seq_in_index = 2 AND column_name = 'user_device_id')
          OR (seq_in_index = 3 AND column_name = 'status')
          OR (seq_in_index = 4 AND column_name = 'source_environment')
          OR (seq_in_index = 5 AND column_name = 'is_deleted')
          OR (seq_in_index = 6 AND column_name = 'task_no'))
    ), 0) = 6)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task' AND index_name = 'idx_task_development_count'
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_INDEX_SHAPE_INVALID_idx_task_development_count');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = (
    SELECT COUNT(*) = 0 OR (COUNT(*) = 5 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND collation = 'A' AND expression IS NULL AND (
          (seq_in_index = 1 AND column_name = 'canonical_event_type')
          OR (seq_in_index = 2 AND column_name = 'is_deleted')
          OR (seq_in_index = 3 AND column_name = 'status')
          OR (seq_in_index = 4 AND column_name = 'id')
          OR (seq_in_index = 5 AND column_name = 'next_retry_at'))
    ), 0) = 5)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND index_name = 'idx_outbox_canonical_type'
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_INDEX_SHAPE_INVALID_idx_outbox_canonical_type');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = (
    SELECT COUNT(*) = 0 OR (COUNT(*) = 5 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND collation = 'A' AND expression IS NULL AND (
          (seq_in_index = 1 AND column_name = 'canonical_event_name')
          OR (seq_in_index = 2 AND column_name = 'is_deleted')
          OR (seq_in_index = 3 AND column_name = 'status')
          OR (seq_in_index = 4 AND column_name = 'id')
          OR (seq_in_index = 5 AND column_name = 'next_retry_at'))
    ), 0) = 5)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND index_name = 'idx_outbox_canonical_name'
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_INDEX_SHAPE_INVALID_idx_outbox_canonical_name');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_old_lock_wait = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 15;

SET @polling_read_column_exists = EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'canonical_event_type'
);
SET @polling_read_sql = IF(@polling_read_column_exists = 0,
    CONCAT('ALTER TABLE nx_event_outbox ADD COLUMN canonical_event_type varchar(96) CHARACTER SET ',
        @polling_read_event_type_charset, ' COLLATE ', @polling_read_event_type_collation,
        ' GENERATED ALWAYS AS (LOWER(event_type)) VIRTUAL, ALGORITHM=INPLACE, LOCK=NONE'),
    'SELECT 1');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'canonical_event_type'
       AND column_type = 'varchar(96)' AND extra LIKE '%VIRTUAL GENERATED%'
       AND is_nullable = 'YES'
       AND LOWER(REPLACE(REPLACE(generation_expression, CHAR(96), ''), ' ', '')) = 'lower(event_type)'
       AND character_set_name = @polling_read_event_type_charset
       AND collation_name = @polling_read_event_type_collation
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_COLUMN_SHAPE_INVALID_canonical_event_type');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_column_exists = EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'canonical_event_name'
);
SET @polling_read_sql = IF(@polling_read_column_exists = 0,
    CONCAT('ALTER TABLE nx_event_outbox ADD COLUMN canonical_event_name varchar(128) CHARACTER SET ',
        @polling_read_event_name_charset, ' COLLATE ', @polling_read_event_name_collation,
        ' GENERATED ALWAYS AS (LOWER(event_name)) VIRTUAL, ALGORITHM=INPLACE, LOCK=NONE'),
    'SELECT 1');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND column_name = 'canonical_event_name'
       AND column_type = 'varchar(128)' AND extra LIKE '%VIRTUAL GENERATED%'
       AND is_nullable = 'YES'
       AND LOWER(REPLACE(REPLACE(generation_expression, CHAR(96), ''), ' ', '')) = 'lower(event_name)'
       AND character_set_name = @polling_read_event_name_charset
       AND collation_name = @polling_read_event_name_collation
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_COLUMN_SHAPE_INVALID_canonical_event_name');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task' AND index_name = 'idx_task_development_count'
);
SET @polling_read_sql = IF(@polling_read_index_exists = 0,
    'ALTER TABLE nx_compute_task ADD INDEX idx_task_development_count (user_id, user_device_id, status, source_environment, is_deleted, task_no), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = (
    SELECT (COUNT(*) = 6 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND collation = 'A' AND expression IS NULL AND (
          (seq_in_index = 1 AND column_name = 'user_id')
          OR (seq_in_index = 2 AND column_name = 'user_device_id')
          OR (seq_in_index = 3 AND column_name = 'status')
          OR (seq_in_index = 4 AND column_name = 'source_environment')
          OR (seq_in_index = 5 AND column_name = 'is_deleted')
          OR (seq_in_index = 6 AND column_name = 'task_no'))
    ), 0) = 6)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task' AND index_name = 'idx_task_development_count'
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_INDEX_SHAPE_INVALID_idx_task_development_count');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND index_name = 'idx_outbox_canonical_type'
);
SET @polling_read_sql = IF(@polling_read_index_exists = 0,
    'ALTER TABLE nx_event_outbox ADD INDEX idx_outbox_canonical_type (canonical_event_type, is_deleted, status, id, next_retry_at), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = (
    SELECT (COUNT(*) = 5 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND collation = 'A' AND expression IS NULL AND (
          (seq_in_index = 1 AND column_name = 'canonical_event_type')
          OR (seq_in_index = 2 AND column_name = 'is_deleted')
          OR (seq_in_index = 3 AND column_name = 'status')
          OR (seq_in_index = 4 AND column_name = 'id')
          OR (seq_in_index = 5 AND column_name = 'next_retry_at'))
    ), 0) = 5)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND index_name = 'idx_outbox_canonical_type'
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_INDEX_SHAPE_INVALID_idx_outbox_canonical_type');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND index_name = 'idx_outbox_canonical_name'
);
SET @polling_read_sql = IF(@polling_read_index_exists = 0,
    'ALTER TABLE nx_event_outbox ADD INDEX idx_outbox_canonical_name (canonical_event_name, is_deleted, status, id, next_retry_at), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET @polling_read_valid = (
    SELECT (COUNT(*) = 5 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND collation = 'A' AND expression IS NULL AND (
          (seq_in_index = 1 AND column_name = 'canonical_event_name')
          OR (seq_in_index = 2 AND column_name = 'is_deleted')
          OR (seq_in_index = 3 AND column_name = 'status')
          OR (seq_in_index = 4 AND column_name = 'id')
          OR (seq_in_index = 5 AND column_name = 'next_retry_at'))
    ), 0) = 5)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_event_outbox' AND index_name = 'idx_outbox_canonical_name'
);
SET @polling_read_sql = IF(@polling_read_valid = 1, 'SELECT 1', 'FAIL POLLING_READ_INDEX_SHAPE_INVALID_idx_outbox_canonical_name');
PREPARE polling_read_stmt FROM @polling_read_sql;
EXECUTE polling_read_stmt;
DEALLOCATE PREPARE polling_read_stmt;

SET SESSION lock_wait_timeout = @polling_read_old_lock_wait;
SELECT RELEASE_LOCK(@polling_read_lock_name);
