-- TEST request authority: additive only; historical unknown requests are deliberately not backfilled.
CREATE TABLE IF NOT EXISTS nx_test_cloud_request (
  executor_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_id BIGINT NOT NULL, device_id BIGINT NOT NULL,
  instance_no VARCHAR(128) NOT NULL, task_config_id VARCHAR(64) NOT NULL,
  task_no VARCHAR(96) NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  generation BIGINT NOT NULL,
  recovery_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  recovery_used TINYINT(1) NOT NULL DEFAULT 0,
  proof_timestamp BIGINT NULL,
  proof_validated TINYINT(1) NOT NULL DEFAULT 0,
  reviewed_termination TINYINT(1) NOT NULL DEFAULT 0,
  response_json JSON NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (executor_id,operation,key_hash),
  KEY idx_test_cloud_request_task (task_no),
  CONSTRAINT chk_test_cloud_request_operation CHECK (operation IN ('NEXT','COMPLETE')),
  CONSTRAINT chk_test_cloud_request_state CHECK (state IN ('PROCESSING','COMMITTED','ROLLED_BACK','UNKNOWN')),
  CONSTRAINT chk_test_cloud_request_generation CHECK (generation >= 1)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS nx_test_cloud_resource_guard (
  id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO nx_test_cloud_resource_guard(id) VALUES(1);

CREATE TABLE IF NOT EXISTS nx_test_cloud_stability_recovery_use (
  review_id BIGINT NOT NULL PRIMARY KEY,
  task_no VARCHAR(96) NOT NULL,
  executor_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  original_body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recovery_body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  termination_authorized TINYINT(1) NOT NULL DEFAULT 0,
  used_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_test_cloud_stability_task (task_no)
) ENGINE=InnoDB;
