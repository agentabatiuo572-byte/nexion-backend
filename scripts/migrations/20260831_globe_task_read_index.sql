-- Read-only Globe aggregation acceleration. Does not change device/task/business rows.
-- Rollback (only after reverting the reader): ALTER TABLE nx_compute_task DROP INDEX idx_compute_task_globe_window;
SET @globe_lock_name = CONCAT('nx:globe:', LEFT(SHA2(DATABASE(), 256), 30));
SELECT GET_LOCK(@globe_lock_name, 10) INTO @globe_lock_acquired;
SET @globe_sql = IF(@globe_lock_acquired = 1, 'SELECT 1', 'FAIL GLOBE_MIGRATION_LOCK_UNAVAILABLE');
PREPARE globe_stmt FROM @globe_sql;
EXECUTE globe_stmt;
DEALLOCATE PREPARE globe_stmt;
SET @globe_old_lock_wait = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 15;
SET @globe_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task'
       AND index_name = 'idx_compute_task_globe_window'
);
SET @globe_sql = IF(@globe_index_exists = 0,
    'ALTER TABLE nx_compute_task ADD INDEX idx_compute_task_globe_window (status, completed_at, user_device_id, is_deleted, source_environment), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE globe_stmt FROM @globe_sql;
EXECUTE globe_stmt;
DEALLOCATE PREPARE globe_stmt;
SET @globe_index_valid = (
    SELECT COUNT(*) = 5 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND ((seq_in_index = 1 AND column_name = 'status' AND collation = 'A')
          OR (seq_in_index = 2 AND column_name = 'completed_at' AND collation = 'A')
          OR (seq_in_index = 3 AND column_name = 'user_device_id' AND collation = 'A')
          OR (seq_in_index = 4 AND column_name = 'is_deleted' AND collation = 'A')
          OR (seq_in_index = 5 AND column_name = 'source_environment' AND collation = 'A'))
    ), 0) = 5
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task'
       AND index_name = 'idx_compute_task_globe_window'
);
SET @globe_sql = IF(@globe_index_valid = 1, 'SELECT 1', 'FAIL GLOBE_INDEX_SHAPE_INVALID');
PREPARE globe_stmt FROM @globe_sql;
EXECUTE globe_stmt;
DEALLOCATE PREPARE globe_stmt;
SET SESSION lock_wait_timeout = @globe_old_lock_wait;
SELECT RELEASE_LOCK(@globe_lock_name);
