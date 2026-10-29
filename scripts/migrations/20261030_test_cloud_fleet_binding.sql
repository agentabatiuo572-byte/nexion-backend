-- Non-secret immutable TEST executor routing. Register only while claiming a paid live instance.
CREATE TABLE IF NOT EXISTS nx_test_cloud_binding (
  executor_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  device_id BIGINT NOT NULL,
  instance_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_config_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model_tag VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model_digest VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_test_cloud_fleet_device(device_id),
  UNIQUE KEY uk_test_cloud_fleet_instance(instance_no),
  KEY idx_test_cloud_fleet_owner(owner_id,device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
