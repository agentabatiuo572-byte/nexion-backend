-- Preserve original Transfer identity while the current canonical position may move after reorg.
SET @cregis_reorg_event_column=IF(NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME='transfer_from_address'),
 'ALTER TABLE nx_cregis_deposit_event ADD COLUMN transfer_from_address VARCHAR(64) NULL', 'SELECT 1');
PREPARE cregis_reorg_stmt FROM @cregis_reorg_event_column;
EXECUTE cregis_reorg_stmt;
DEALLOCATE PREPARE cregis_reorg_stmt;
SET @cregis_reorg_event_column=IF(NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME='origin_log_index'),
 'ALTER TABLE nx_cregis_deposit_event ADD COLUMN origin_log_index INT NULL', 'SELECT 1');
PREPARE cregis_reorg_stmt FROM @cregis_reorg_event_column;
EXECUTE cregis_reorg_stmt;
DEALLOCATE PREPARE cregis_reorg_stmt;
UPDATE nx_cregis_deposit_event SET origin_log_index=log_index WHERE origin_log_index IS NULL;

-- The incident auto-increment ID is its epoch. Event row + provision-gate locks serialize each new freeze.
SET @cregis_reorg_drop_legacy_unique = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_incident'
 AND INDEX_NAME='uk_cregis_incident_kind' AND NON_UNIQUE=0),
 'ALTER TABLE nx_cregis_deposit_incident DROP INDEX uk_cregis_incident_kind, ADD KEY idx_cregis_incident_event_kind(event_id,kind)', 'SELECT 1');
PREPARE cregis_reorg_stmt FROM @cregis_reorg_drop_legacy_unique;
EXECUTE cregis_reorg_stmt;
DEALLOCATE PREPARE cregis_reorg_stmt;

CREATE TABLE IF NOT EXISTS nx_cregis_reorg_resolution (
  incident_id BIGINT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  cid BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  net_amount DECIMAL(18,6) NOT NULL,
  held_amount DECIMAL(18,6) NOT NULL,
  recovered_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  outstanding_debt DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'INVESTIGATING',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_cregis_reorg_event(event_id),
  KEY idx_cregis_reorg_user(user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_reorg_proposal (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  expected_version BIGINT NOT NULL,
  evidence_hash CHAR(64) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  maker_id BIGINT NOT NULL,
  checker_id BIGINT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'MAKER_DONE',
  version BIGINT NOT NULL DEFAULT 0,
  pending_incident BIGINT GENERATED ALWAYS AS (CASE WHEN status='MAKER_DONE' THEN incident_id ELSE NULL END) STORED,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_cregis_reorg_pending(pending_incident)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_reorg_operation (
  operation_no VARCHAR(96) PRIMARY KEY,
  incident_id BIGINT NOT NULL,
  resolution_version BIGINT NOT NULL,
  phase VARCHAR(32) NOT NULL,
  original_fact_id VARCHAR(96) NOT NULL,
  attribution_effect VARCHAR(24) NOT NULL,
  snapshot_json JSON NOT NULL,
  proof_json JSON NOT NULL,
  events_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_cregis_reorg_operation(incident_id,resolution_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve old incidents. No wallet, ledger or reserve is changed by migration.
INSERT IGNORE INTO nx_cregis_reorg_resolution
 (incident_id,event_id,project_id,cid,user_id,net_amount,held_amount)
SELECT i.id,i.event_id,i.project_id,i.cid,i.user_id,e.net_amount,i.held_amount
  FROM nx_cregis_deposit_incident i JOIN nx_cregis_deposit_event e ON e.id=i.event_id
 WHERE i.kind IN ('REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD') AND e.net_amount>0;

-- Compensation history is append-only, like the existing support fund receipts.
DELIMITER $$
CREATE TRIGGER IF NOT EXISTS nx_cregis_reorg_operation_bu_v1
BEFORE UPDATE ON nx_cregis_reorg_operation FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CREGIS_REORG_OPERATION_IMMUTABLE';
END$$
CREATE TRIGGER IF NOT EXISTS nx_cregis_reorg_operation_bd_v1
BEFORE DELETE ON nx_cregis_reorg_operation FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CREGIS_REORG_OPERATION_IMMUTABLE';
END$$
DELIMITER ;
