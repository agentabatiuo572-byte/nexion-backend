-- Fixed USDT-BEP20 project source mutex, EIP191 challenges and two-person claims.
CREATE TABLE IF NOT EXISTS nx_cregis_exception_source (
 project_id BIGINT NOT NULL, txid VARCHAR(66) NOT NULL, log_index INT NOT NULL,
 cid BIGINT NOT NULL, address VARCHAR(64) NOT NULL, gross_amount DECIMAL(18,6) NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE', operation_kind VARCHAR(16) NULL,
 operation_ref VARCHAR(96) NULL, alias_of_log_index INT NULL, version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(project_id,txid,log_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_cregis_ownership_challenge (
 challenge_id VARCHAR(36) PRIMARY KEY, project_id BIGINT NOT NULL,
 source_type VARCHAR(16) NOT NULL, source_id BIGINT NOT NULL, action VARCHAR(24) NOT NULL,
 target_user_id BIGINT NULL, message TEXT NOT NULL, evidence_hash VARCHAR(64) NOT NULL,
 expires_at DATETIME NOT NULL, consumed_ref VARCHAR(96) NULL,
 created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_cregis_challenge_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_cregis_exception_claim (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, case_no VARCHAR(64) NOT NULL,
 project_id BIGINT NOT NULL, source_type VARCHAR(16) NOT NULL, source_id BIGINT NOT NULL,
 cid BIGINT NOT NULL, txid VARCHAR(66) NOT NULL, log_index INT NOT NULL,
 address VARCHAR(64) NOT NULL, from_address VARCHAR(64) NOT NULL, gross_amount DECIMAL(18,6) NOT NULL,
 target_user_id BIGINT NOT NULL, challenge_id VARCHAR(36) NOT NULL, evidence_hash VARCHAR(64) NOT NULL,
 maker_id BIGINT NOT NULL, checker_id BIGINT NULL, reason VARCHAR(300) NOT NULL,
 decision_reason VARCHAR(300) NULL, state VARCHAR(24) NOT NULL DEFAULT 'MAKER_DONE', version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, resolved_at DATETIME NULL,
 UNIQUE KEY uk_cregis_claim_no(case_no), UNIQUE KEY uk_cregis_claim_challenge(challenge_id),
 INDEX idx_cregis_claim_page(project_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
