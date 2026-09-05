-- App statistics read acceleration only: no balances, receipts or account data are changed.
-- Keep existing indexes for other consumers. Reruns verify shape and fail on name collisions.
-- Split the virtual column and its index so both steps permit concurrent writes.
-- Its expression matches the original latest-client timestamp/tie order exactly.
SET @app_stats_lock_name = CONCAT('nx:appstats:', LEFT(SHA2(DATABASE(), 256), 30));
SELECT GET_LOCK(@app_stats_lock_name, 10) INTO @app_stats_lock_acquired;
SET @app_stats_sql = IF(@app_stats_lock_acquired = 1, 'SELECT 1', 'FAIL APP_STATS_MIGRATION_LOCK_UNAVAILABLE');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;

-- Direct predicates replace UPPER/COALESCE only under these schema guarantees.
SET @app_stats_schema_valid = (
    SELECT COUNT(*) = 4 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND is_nullable = 'NO'
       AND RIGHT(collation_name, 3) = '_ci'
       AND ((table_name = 'nx_compute_receipt' AND column_name IN ('earning_status','source_environment'))
         OR (table_name = 'nx_compute_task' AND column_name IN ('status','source_environment')))
);
SET @app_stats_sql = IF(@app_stats_schema_valid = 1, 'SELECT 1', 'FAIL APP_STATS_SCHEMA_PRECONDITION_FAILED');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;

SET @app_stats_old_lock_wait = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 15;

SET @app_stats_latest_column_exists = EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task' AND column_name = 'client_observed_at'
);
SET @app_stats_sql = IF(@app_stats_latest_column_exists = 0,
    'ALTER TABLE nx_compute_task ADD COLUMN client_observed_at DATETIME GENERATED ALWAYS AS (COALESCE(completed_at, updated_at, created_at)) VIRTUAL, ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;
SET @app_stats_latest_column_valid = EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task' AND column_name = 'client_observed_at'
       AND column_type = 'datetime' AND extra LIKE '%VIRTUAL GENERATED%'
       AND LOWER(REPLACE(REPLACE(generation_expression, '`', ''), ' ', '')) = 'coalesce(completed_at,updated_at,created_at)'
);
SET @app_stats_sql = IF(@app_stats_latest_column_valid = 1, 'SELECT 1', 'FAIL APP_STATS_LATEST_COLUMN_SHAPE_INVALID');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;

SET @app_stats_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND index_name = 'idx_receipt_user_earnings'
);
SET @app_stats_sql = IF(@app_stats_index_exists = 0,
    'ALTER TABLE nx_compute_receipt ADD INDEX idx_receipt_user_earnings (user_id, source_environment, is_deleted, earning_status, completed_at, reward_usdt, reward_nex), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;
SET @app_stats_index_valid = (
    SELECT COUNT(*) = 7 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND ((seq_in_index = 1 AND column_name = 'user_id' AND collation = 'A')
        OR (seq_in_index = 2 AND column_name = 'source_environment' AND collation = 'A')
        OR (seq_in_index = 3 AND column_name = 'is_deleted' AND collation = 'A')
        OR (seq_in_index = 4 AND column_name = 'earning_status' AND collation = 'A')
        OR (seq_in_index = 5 AND column_name = 'completed_at' AND collation = 'A')
        OR (seq_in_index = 6 AND column_name = 'reward_usdt' AND collation = 'A')
        OR (seq_in_index = 7 AND column_name = 'reward_nex' AND collation = 'A'))
    ), 0) = 7
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND index_name = 'idx_receipt_user_earnings'
);
SET @app_stats_sql = IF(@app_stats_index_valid = 1, 'SELECT 1', 'FAIL APP_STATS_INDEX_SHAPE_INVALID_idx_receipt_user_earnings');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;

SET @app_stats_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND index_name = 'idx_receipt_device_earnings'
);
SET @app_stats_sql = IF(@app_stats_index_exists = 0,
    'ALTER TABLE nx_compute_receipt ADD INDEX idx_receipt_device_earnings (user_device_id, source_environment, is_deleted, earning_status, reward_usdt), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;
SET @app_stats_index_valid = (
    SELECT COUNT(*) = 5 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND ((seq_in_index = 1 AND column_name = 'user_device_id' AND collation = 'A')
        OR (seq_in_index = 2 AND column_name = 'source_environment' AND collation = 'A')
        OR (seq_in_index = 3 AND column_name = 'is_deleted' AND collation = 'A')
        OR (seq_in_index = 4 AND column_name = 'earning_status' AND collation = 'A')
        OR (seq_in_index = 5 AND column_name = 'reward_usdt' AND collation = 'A'))
    ), 0) = 5
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND index_name = 'idx_receipt_device_earnings'
);
SET @app_stats_sql = IF(@app_stats_index_valid = 1, 'SELECT 1', 'FAIL APP_STATS_INDEX_SHAPE_INVALID_idx_receipt_device_earnings');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;

SET @app_stats_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task' AND index_name = 'idx_task_device_latest_client'
);
SET @app_stats_sql = IF(@app_stats_index_exists = 0,
    'ALTER TABLE nx_compute_task ADD INDEX idx_task_device_latest_client (user_device_id, is_deleted, client_observed_at DESC, id DESC), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;
SET @app_stats_index_valid = (
    SELECT COUNT(*) = 4 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND ((seq_in_index = 1 AND column_name = 'user_device_id' AND collation = 'A')
        OR (seq_in_index = 2 AND column_name = 'is_deleted' AND collation = 'A')
        OR (seq_in_index = 3 AND column_name = 'client_observed_at' AND collation = 'D')
        OR (seq_in_index = 4 AND column_name = 'id' AND collation = 'D'))
    ), 0) = 4
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_compute_task' AND index_name = 'idx_task_device_latest_client'
);
SET @app_stats_sql = IF(@app_stats_index_valid = 1, 'SELECT 1', 'FAIL APP_STATS_INDEX_SHAPE_INVALID_idx_task_device_latest_client');
PREPARE app_stats_stmt FROM @app_stats_sql;
EXECUTE app_stats_stmt;
DEALLOCATE PREPARE app_stats_stmt;

SET SESSION lock_wait_timeout = @app_stats_old_lock_wait;
SELECT RELEASE_LOCK(@app_stats_lock_name);
