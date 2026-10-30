-- Empty by default. Reviewed TEST identities only; this grants a date exception, never execution or payment proof.
CREATE TABLE IF NOT EXISTS nx_test_cloud_legacy_admission (
  device_id BIGINT NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  instance_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_order_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_channel VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  old_task_no VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_config_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  deployment_scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'TEST',
  enabled TINYINT NOT NULL DEFAULT 0,
  reason VARCHAR(512) NOT NULL,
  evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reviewed_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_test_cloud_legacy_instance(instance_no),
  UNIQUE KEY uk_test_cloud_legacy_task(old_task_no),
  CONSTRAINT ck_test_cloud_legacy_scope CHECK (deployment_scope='TEST'),
  CONSTRAINT ck_test_cloud_legacy_channel CHECK (source_channel IN ('ORDER','TRADE_IN')),
  CONSTRAINT ck_test_cloud_legacy_review CHECK (enabled IN (0,1) AND CHAR_LENGTH(reason)>0
    AND evidence_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
