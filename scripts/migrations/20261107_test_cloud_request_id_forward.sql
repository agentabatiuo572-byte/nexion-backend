-- Forward-only request identity upgrade; historical migration bytes stay unchanged.
-- Self-contained for catalogs whose historical CREATE was baselined without replay.
CREATE TABLE IF NOT EXISTS nx_test_cloud_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
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
  PRIMARY KEY (id),
  UNIQUE KEY uk_test_cloud_request_identity (executor_id,operation,key_hash),
  KEY idx_test_cloud_request_task (task_no),
  CONSTRAINT chk_test_cloud_request_operation CHECK (operation IN ('NEXT','COMPLETE')),
  CONSTRAINT chk_test_cloud_request_state CHECK (state IN ('PROCESSING','COMMITTED','ROLLED_BACK','UNKNOWN')),
  CONSTRAINT chk_test_cloud_request_generation CHECK (generation >= 1)
) ENGINE=InnoDB;

-- Convert the early composite primary key in one atomic DDL without rewriting
-- request state, proof, recovery, response or financial facts.
SET @test_cloud_request_upgrade_sql = IF((
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_test_cloud_request' AND COLUMN_NAME='id'
)=0,
  'ALTER TABLE nx_test_cloud_request DROP PRIMARY KEY, ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST, ADD PRIMARY KEY (id), ADD UNIQUE KEY uk_test_cloud_request_identity (executor_id,operation,key_hash)',
  'SELECT 1');
PREPARE test_cloud_request_upgrade_stmt FROM @test_cloud_request_upgrade_sql;
EXECUTE test_cloud_request_upgrade_stmt;
DEALLOCATE PREPARE test_cloud_request_upgrade_stmt;
