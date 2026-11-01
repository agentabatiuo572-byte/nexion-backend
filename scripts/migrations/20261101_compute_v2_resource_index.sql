-- Both legacy and V2 admission count unresolved legacy requests. Lead with state
-- so historical COMMITTED rows are excluded before resolving device ownership.
-- Named, forward-only addition; no changes to requests, journals or replay rights.
SET @compute_v2_resource_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_test_cloud_request'
    AND index_name='idx_state_device_owner'
);
SET @compute_v2_resource_index_sql = IF(@compute_v2_resource_index_exists=0,
  'ALTER TABLE nx_test_cloud_request ADD INDEX idx_state_device_owner(state,device_id,owner_id)',
  'SELECT 1');
PREPARE compute_v2_resource_index_stmt FROM @compute_v2_resource_index_sql;
EXECUTE compute_v2_resource_index_stmt;
DEALLOCATE PREPARE compute_v2_resource_index_stmt;
