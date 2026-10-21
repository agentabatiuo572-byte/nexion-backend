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
 consumed_signature VARCHAR(132) NULL, signer VARCHAR(42) NULL, consumed_at DATETIME NULL,
 created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_cregis_challenge_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotent upgrade of a challenge table created by an earlier candidate.
SET @cregis_ownership_signature_ddl=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_ownership_challenge' AND COLUMN_NAME='consumed_signature'),
 'SELECT 1','ALTER TABLE nx_cregis_ownership_challenge ADD COLUMN consumed_signature VARCHAR(132) NULL');
PREPARE cregis_ownership_signature_stmt FROM @cregis_ownership_signature_ddl;
EXECUTE cregis_ownership_signature_stmt;
DEALLOCATE PREPARE cregis_ownership_signature_stmt;
SET @cregis_ownership_signer_ddl=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_ownership_challenge' AND COLUMN_NAME='signer'),
 'SELECT 1','ALTER TABLE nx_cregis_ownership_challenge ADD COLUMN signer VARCHAR(42) NULL');
PREPARE cregis_ownership_signer_stmt FROM @cregis_ownership_signer_ddl;
EXECUTE cregis_ownership_signer_stmt;
DEALLOCATE PREPARE cregis_ownership_signer_stmt;
SET @cregis_ownership_consumed_at_ddl=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_ownership_challenge' AND COLUMN_NAME='consumed_at'),
 'SELECT 1','ALTER TABLE nx_cregis_ownership_challenge ADD COLUMN consumed_at DATETIME NULL');
PREPARE cregis_ownership_consumed_at_stmt FROM @cregis_ownership_consumed_at_ddl;
EXECUTE cregis_ownership_consumed_at_stmt;
DEALLOCATE PREPARE cregis_ownership_consumed_at_stmt;

-- The issued challenge is immutable; its complete EOA receipt can be written exactly once.
DELIMITER $$
CREATE TRIGGER IF NOT EXISTS nx_cregis_ownership_challenge_bi_v1
BEFORE INSERT ON nx_cregis_ownership_challenge FOR EACH ROW
BEGIN
 IF NEW.consumed_ref IS NOT NULL OR NEW.consumed_signature IS NOT NULL OR NEW.signer IS NOT NULL OR NEW.consumed_at IS NOT NULL THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CREGIS_OWNERSHIP_RECEIPT_MUST_START_UNUSED';
 END IF;
END$$
CREATE TRIGGER IF NOT EXISTS nx_cregis_ownership_challenge_bu_v1
BEFORE UPDATE ON nx_cregis_ownership_challenge FOR EACH ROW
BEGIN
 IF NOT(NEW.challenge_id<=>OLD.challenge_id AND NEW.project_id<=>OLD.project_id
    AND NEW.source_type<=>OLD.source_type AND NEW.source_id<=>OLD.source_id
    AND NEW.action<=>OLD.action AND NEW.target_user_id<=>OLD.target_user_id
    AND BINARY NEW.message<=>BINARY OLD.message AND NEW.evidence_hash<=>OLD.evidence_hash
    AND NEW.expires_at<=>OLD.expires_at AND NEW.created_by<=>OLD.created_by AND NEW.created_at<=>OLD.created_at)
    OR OLD.consumed_ref IS NOT NULL OR OLD.consumed_signature IS NOT NULL
    OR OLD.signer IS NOT NULL OR OLD.consumed_at IS NOT NULL
    OR NEW.consumed_ref IS NULL OR NEW.consumed_signature IS NULL OR NEW.signer IS NULL OR NEW.consumed_at IS NULL
    OR NEW.consumed_at>OLD.expires_at THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CREGIS_OWNERSHIP_RECEIPT_IMMUTABLE';
 END IF;
END$$
CREATE TRIGGER IF NOT EXISTS nx_cregis_ownership_challenge_bd_v1
BEFORE DELETE ON nx_cregis_ownership_challenge FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CREGIS_OWNERSHIP_RECEIPT_IMMUTABLE';
END$$
DELIMITER ;
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
