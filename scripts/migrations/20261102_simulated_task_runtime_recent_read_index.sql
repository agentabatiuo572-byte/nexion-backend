-- Bound account polling to the latest completed tasks for each owned device.
SET @sim_recent_index_sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_simulated_task'
    AND INDEX_NAME='idx_simulated_task_recent')=0,
  'ALTER TABLE nx_simulated_task ADD INDEX idx_simulated_task_recent (user_id,user_device_id,status,completed_at DESC,task_no DESC)',
  'SELECT 1');
PREPARE sim_recent_index_stmt FROM @sim_recent_index_sql;
EXECUTE sim_recent_index_stmt;
DEALLOCATE PREPARE sim_recent_index_stmt;
