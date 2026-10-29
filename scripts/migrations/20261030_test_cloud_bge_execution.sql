-- Explicitly TEST-only real BGE-M3 inference manifest. No business task, wallet or historical record is changed.
CREATE TABLE IF NOT EXISTS nx_test_cloud_execution (
  task_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  executor_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_id BIGINT NOT NULL,
  device_id BIGINT NOT NULL,
  instance_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_config_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  next_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  claim_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_json TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model_tag VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model_digest VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  proof_nonce CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  result_artifact MEDIUMBLOB NULL,
  complete_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  complete_body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_test_cloud_next (executor_id,next_key_hash),
  KEY idx_test_cloud_binding (owner_id,device_id),
  CONSTRAINT ck_test_cloud_input CHECK (JSON_VALID(input_json)),
  CONSTRAINT ck_test_cloud_result CHECK ((result_hash IS NULL AND result_artifact IS NULL AND complete_key_hash IS NULL AND complete_body_hash IS NULL)
    OR (result_hash IS NOT NULL AND result_artifact IS NOT NULL AND OCTET_LENGTH(result_artifact)=12288 AND complete_key_hash IS NOT NULL AND complete_body_hash IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
