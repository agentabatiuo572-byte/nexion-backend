CREATE TABLE IF NOT EXISTS nx_cregis_exception_refund_policy (
  project_id BIGINT PRIMARY KEY, mode VARCHAR(24) NOT NULL DEFAULT 'UNCONFIGURED',
  fee_usdt DECIMAL(18,6) NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_cregis_exception_refund_policy_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, project_id BIGINT NOT NULL, expected_policy_version BIGINT NOT NULL,
  mode VARCHAR(24) NOT NULL, fee_usdt DECIMAL(18,6) NOT NULL, maker_id BIGINT NOT NULL, checker_id BIGINT NULL,
  state VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0, reason VARCHAR(300) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, checked_at DATETIME NULL,
  KEY idx_refund_policy_project (project_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_cregis_exception_refund (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, case_no VARCHAR(64) NOT NULL, project_id BIGINT NOT NULL,
  source_type VARCHAR(16) NOT NULL, source_id BIGINT NOT NULL, claimant_user_id BIGINT NULL,
  txid VARCHAR(66) NOT NULL, log_index INT NOT NULL, deposit_cid BIGINT NULL,
  return_address VARCHAR(42) NOT NULL, deposit_address VARCHAR(42) NOT NULL,
  gross_amount DECIMAL(18,6) NOT NULL, fee_amount DECIMAL(18,6) NOT NULL, net_amount DECIMAL(18,6) NOT NULL,
  fee_policy_version BIGINT NOT NULL, ownership_receipt VARCHAR(128) NOT NULL, evidence_hash CHAR(64) NOT NULL,
  ownership_reference VARCHAR(64) NOT NULL,
  source_evidence_hash CHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0, maker_id BIGINT NOT NULL, checker_id BIGINT NULL,
  reason VARCHAR(300) NOT NULL, provider_cid BIGINT NULL, business_key VARCHAR(64) NOT NULL,
  dispatch_intent_at DATETIME NULL, chain_txid VARCHAR(66) NULL, last_error VARCHAR(120) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_exception_refund_no (case_no), UNIQUE KEY uk_exception_refund_key (business_key),
  KEY idx_exception_refund_source (project_id,txid,log_index), KEY idx_exception_refund_state (state,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_cregis_exception_refund_callback (
  payload_hash CHAR(64) PRIMARY KEY, case_no VARCHAR(64) NOT NULL, provider_cid BIGINT NOT NULL,
  provider_status INT NOT NULL, txid VARCHAR(66) NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_exception_refund_callback (case_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_cregis_exception_refund_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, case_no VARCHAR(64) NOT NULL, event_type VARCHAR(32) NOT NULL,
  provider_cid BIGINT NULL, amount_usdt DECIMAL(18,6) NOT NULL, txid VARCHAR(66) NULL,
  evidence_json JSON NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_exception_refund_event (case_no,event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
