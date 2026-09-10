-- App task-assignment preview only. No task, receipt, wallet, or device rows are changed.
-- The matching reader fetches active tasks plus the latest ten completed tasks per owned device.
-- Review before execution. Rollback after reverting that reader:
--   ALTER TABLE nx_compute_task DROP INDEX idx_compute_task_assignment_recent;
SET @assignment_read_lock_name = CONCAT('nx:assignment-read:', LEFT(SHA2(DATABASE(), 256), 30));
SELECT GET_LOCK(@assignment_read_lock_name, 10) INTO @assignment_read_lock_acquired;
SET @assignment_read_sql = IF(@assignment_read_lock_acquired = 1, 'SELECT 1',
    'FAIL ASSIGNMENT_READ_MIGRATION_LOCK_UNAVAILABLE');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;

-- Direct status/source comparisons are equivalent to the legacy UPPER predicates only on CI columns.
SET @assignment_read_schema_valid = (
    SELECT COUNT(*) = 2 AND COALESCE(SUM(
        is_nullable = 'NO' AND RIGHT(collation_name, 3) = '_ci'
        AND column_name IN ('status', 'source_environment')
    ), 0) = 2
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task'
       AND column_name IN ('status', 'source_environment')
);
SET @assignment_read_sql = IF(@assignment_read_schema_valid = 1, 'SELECT 1',
    'FAIL ASSIGNMENT_READ_SCHEMA_PRECONDITION_FAILED');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;

SET @assignment_read_old_lock_wait = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 15;
SET @assignment_read_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task'
       AND index_name = 'idx_compute_task_assignment_recent'
);
SET @assignment_read_sql = IF(@assignment_read_index_exists = 0,
    'ALTER TABLE nx_compute_task ADD INDEX idx_compute_task_assignment_recent (user_id, user_device_id, source_environment, is_deleted, status, created_at DESC, id DESC), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;

SET @assignment_read_index_valid = (
    SELECT COUNT(*) = 7 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND ((seq_in_index = 1 AND column_name = 'user_id' AND collation = 'A')
          OR (seq_in_index = 2 AND column_name = 'user_device_id' AND collation = 'A')
          OR (seq_in_index = 3 AND column_name = 'source_environment' AND collation = 'A')
          OR (seq_in_index = 4 AND column_name = 'is_deleted' AND collation = 'A')
          OR (seq_in_index = 5 AND column_name = 'status' AND collation = 'A')
          OR (seq_in_index = 6 AND column_name = 'created_at' AND collation = 'D')
          OR (seq_in_index = 7 AND column_name = 'id' AND collation = 'D'))
    ), 0) = 7
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task'
       AND index_name = 'idx_compute_task_assignment_recent'
);
SET @assignment_read_sql = IF(@assignment_read_index_valid = 1, 'SELECT 1',
    'FAIL ASSIGNMENT_READ_INDEX_SHAPE_INVALID');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;

SET SESSION lock_wait_timeout = @assignment_read_old_lock_wait;
SELECT RELEASE_LOCK(@assignment_read_lock_name);
