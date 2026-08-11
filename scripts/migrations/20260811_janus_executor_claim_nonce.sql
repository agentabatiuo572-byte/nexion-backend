CREATE TABLE IF NOT EXISTS nx_janus_executor_claim_nonce (
  executor_id VARCHAR(128) NOT NULL,
  claim_nonce VARCHAR(128) NOT NULL,
  claim_hash CHAR(64) NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  proof_timestamp DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (executor_id, claim_nonce),
  KEY idx_janus_executor_claim_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS nx_janus_command_lease (
  device_id VARCHAR(128) NOT NULL,
  command_id VARCHAR(128) NOT NULL,
  command_version BIGINT NOT NULL,
  executor_id VARCHAR(128) NOT NULL,
  claim_nonce VARCHAR(128) NOT NULL,
  lease_token CHAR(64) NOT NULL,
  fencing_token BIGINT NOT NULL DEFAULT 1,
  lease_until DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (device_id, command_id, command_version),
  UNIQUE KEY uk_janus_command_lease_token (lease_token),
  KEY idx_janus_command_lease_expiry (lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE nx_janus_applied_proof MODIFY proof_nonce VARCHAR(128) NOT NULL;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_earnings_release_attestation'
    AND column_name='source_environment')=0,
  'ALTER TABLE nx_earnings_release_attestation ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER device_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_earnings_release_attestation'
    AND index_name='uk_earnings_release_attestation' AND seq_in_index=3)=0,
  'ALTER TABLE nx_earnings_release_attestation DROP INDEX uk_earnings_release_attestation, ADD UNIQUE KEY uk_earnings_release_attestation(user_id,device_id,source_environment)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
