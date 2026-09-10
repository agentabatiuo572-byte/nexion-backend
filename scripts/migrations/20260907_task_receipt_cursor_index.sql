-- App compute-receipt cursor reads only. No receipt, task, wallet, or device facts are changed.
-- Existing correctly shaped indexes are left untouched. A conflicting named index fails closed for review.
SET @task_receipt_cursor_lock_name = CONCAT('nx:task-receipt-cursor:', LEFT(SHA2(DATABASE(), 256), 30));
SELECT GET_LOCK(@task_receipt_cursor_lock_name, 10) INTO @task_receipt_cursor_lock_acquired;
SET @task_receipt_cursor_sql = IF(@task_receipt_cursor_lock_acquired = 1, 'SELECT 1',
    'FAIL TASK_RECEIPT_CURSOR_MIGRATION_LOCK_UNAVAILABLE');
PREPARE task_receipt_cursor_stmt FROM @task_receipt_cursor_sql;
EXECUTE task_receipt_cursor_stmt;
DEALLOCATE PREPARE task_receipt_cursor_stmt;

SET @task_receipt_cursor_old_lock_wait = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 15;
SET @task_receipt_cursor_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt'
       AND index_name = 'idx_receipt_user_time'
);
SET @task_receipt_cursor_sql = IF(@task_receipt_cursor_index_exists = 0,
    'ALTER TABLE nx_compute_receipt ADD INDEX idx_receipt_user_time (user_id, completed_at), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE task_receipt_cursor_stmt FROM @task_receipt_cursor_sql;
EXECUTE task_receipt_cursor_stmt;
DEALLOCATE PREPARE task_receipt_cursor_stmt;

SET @task_receipt_cursor_index_valid = (
    SELECT COUNT(*) = 2 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND ((seq_in_index = 1 AND column_name = 'user_id' AND collation = 'A')
          OR (seq_in_index = 2 AND column_name = 'completed_at' AND collation = 'A'))
    ), 0) = 2
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt'
       AND index_name = 'idx_receipt_user_time'
);
SET @task_receipt_cursor_sql = IF(@task_receipt_cursor_index_valid = 1, 'SELECT 1',
    'FAIL TASK_RECEIPT_CURSOR_INDEX_SHAPE_INVALID');
PREPARE task_receipt_cursor_stmt FROM @task_receipt_cursor_sql;
EXECUTE task_receipt_cursor_stmt;
DEALLOCATE PREPARE task_receipt_cursor_stmt;

SET SESSION lock_wait_timeout = @task_receipt_cursor_old_lock_wait;
SELECT RELEASE_LOCK(@task_receipt_cursor_lock_name);
