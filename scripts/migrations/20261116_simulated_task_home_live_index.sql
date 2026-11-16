-- Cover current RUNNING task queries; compatible with a pre-created exact index.
-- Non-unique full-column index only; no business rows or existing indexes are changed.
-- Rollback: ALTER TABLE nx_simulated_task DROP INDEX idx_sim_task_home_live_cov, ALGORITHM=INPLACE, LOCK=NONE;
SET @sim_task_home_live_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_simulated_task'
       AND index_name = 'idx_sim_task_home_live_cov'
);
SET @sim_task_home_live_sql = IF(@sim_task_home_live_index_exists = 0,
    'ALTER TABLE nx_simulated_task ADD INDEX idx_sim_task_home_live_cov (status, due_at, task_no, user_id, user_device_id, generation, instance_no, started_at), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE sim_task_home_live_stmt FROM @sim_task_home_live_sql;
EXECUTE sim_task_home_live_stmt;
DEALLOCATE PREPARE sim_task_home_live_stmt;

SET @sim_task_home_live_index_valid = (
    SELECT COUNT(*) = 8 AND MIN(non_unique) = 1 AND MAX(non_unique) = 1
       AND SUM(sub_part IS NOT NULL) = 0 AND SUM(is_visible != 'YES') = 0
       AND SUM(index_type != 'BTREE') = 0 AND SUM(collation != 'A') = 0
       AND LOWER(REPLACE(REPLACE(GROUP_CONCAT(COALESCE(column_name, expression)
           ORDER BY seq_in_index SEPARATOR '|'), CHAR(96), ''), ' ', '')) = 'status|due_at|task_no|user_id|user_device_id|generation|instance_no|started_at'
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_simulated_task'
       AND index_name = 'idx_sim_task_home_live_cov'
);
SET @sim_task_home_live_sql = IF(@sim_task_home_live_index_valid = 1,
    'SELECT 1', 'FAIL SIM_TASK_HOME_LIVE_INDEX_SHAPE_INVALID');
PREPARE sim_task_home_live_stmt FROM @sim_task_home_live_sql;
EXECUTE sim_task_home_live_stmt;
DEALLOCATE PREPARE sim_task_home_live_stmt;
