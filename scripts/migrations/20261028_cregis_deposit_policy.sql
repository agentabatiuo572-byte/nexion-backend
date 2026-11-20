-- The release broker stops the old backend before applying this file.
-- Fail closed before any persistent DDL when an earlier discovery is not proven complete.
-- The marker is written only after all schema and historical snapshots succeed.
-- A missing marker means an interrupted initial install must run the full preflight again.
SET @cregis_policy_installed = 0;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_policy_install')=1,
  'SELECT COUNT(*) INTO @cregis_policy_installed FROM nx_cregis_deposit_policy_install WHERE id=1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @cregis_policy_schema_ready = (SELECT COUNT(*)=3 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME IN ('nx_cregis_deposit_policy','nx_cregis_deposit_policy_snapshot','nx_cregis_deposit_policy_lock'))
  AND (SELECT COUNT(*)=8 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND (TABLE_NAME='nx_cregis_deposit_policy_snapshot' AND COLUMN_NAME IN ('project_id','chain_id','token_id','txid','policy_version','required_confirmations')
       OR TABLE_NAME='nx_cregis_exception_claim' AND COLUMN_NAME IN ('policy_version','policy_fee_usdt')));
SET @cregis_policy_data_ready = 0;
SET @sql = IF(@cregis_policy_schema_ready,
  'SELECT ((SELECT COUNT(*) FROM nx_cregis_deposit_policy)>0 AND (SELECT COUNT(*) FROM nx_cregis_deposit_policy_lock WHERE id=1)=1) INTO @cregis_policy_data_ready', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
CREATE TEMPORARY TABLE cregis_legacy_preflight (unresolved BIGINT NOT NULL CHECK (unresolved=0));
INSERT INTO cregis_legacy_preflight(unresolved)
SELECT IF(@cregis_policy_installed=1,IF(@cregis_policy_schema_ready AND @cregis_policy_data_ready,0,1),
 (SELECT COUNT(*) FROM nx_cregis_deposit_event e
  WHERE e.status <> 'CREDITED' OR e.status IS NULL OR e.ledger_id IS NULL OR e.project_id <= 0 OR e.cid <= 0
     OR e.txid IS NULL OR NOT (e.txid REGEXP '^(0x)?[0-9A-Fa-f]{64}$') OR e.address IS NULL OR NOT (e.address REGEXP '^0x[0-9A-Fa-f]{40}$')
     OR e.block_number IS NULL OR e.block_hash IS NULL OR e.log_index IS NULL OR e.user_id IS NULL
     OR e.confirmations IS NULL OR e.confirmations < 15 OR e.gross_amount IS NULL OR e.fee_amount IS NULL OR e.net_amount IS NULL
     OR e.fee_amount <> 1 OR e.net_amount <= 0 OR e.net_amount <> e.gross_amount-1
     OR e.canonical_until IS NULL OR e.last_canonical_checked_block IS NULL OR e.last_canonical_checked_block < e.canonical_until
     OR (SELECT COUNT(*) FROM nx_wallet_ledger l WHERE l.id=e.ledger_id AND l.biz_no=CONCAT('CR-',e.cid) AND l.user_id=e.user_id AND l.asset='USDT' AND l.direction='IN' AND l.biz_type='CHAIN_TOPUP' AND l.is_deleted=0 AND l.amount=e.net_amount AND l.status='SUCCESS') <> 1
     OR (SELECT COUNT(*) FROM nx_deposit_order o WHERE o.ledger_id=e.ledger_id AND o.deposit_no=CONCAT('CR-',e.cid) AND o.user_id=e.user_id AND o.asset='USDT' AND o.chain_name='CREGIS_USDT_BEP20' AND REPLACE(LOWER(o.chain_tx_hash),'0x','')=REPLACE(LOWER(e.txid),'0x','') AND o.chain_log_index=e.log_index AND o.is_deleted=0 AND o.amount=e.net_amount AND o.status='CREDITED') <> 1
     OR (SELECT COUNT(*) FROM nx_treasury_reserve_ledger r WHERE r.voucher_no=CONCAT('CR-',e.cid) AND r.idempotency_key=CONCAT('CREGIS:',e.cid) AND r.direction='IN' AND r.amount_usd=e.net_amount AND r.status='CONFIRMED' AND r.is_deleted=0) <> 1)
 + (SELECT COUNT(*) FROM nx_cregis_chain_observation o WHERE o.status IS NULL OR o.status <> 'MATCHED' OR o.txid IS NULL OR NOT (o.txid REGEXP '^(0x)?[0-9A-Fa-f]{64}$')
    OR NOT EXISTS (SELECT 1 FROM nx_cregis_deposit_event e WHERE e.project_id=o.project_id AND REPLACE(LOWER(e.txid),'0x','')=REPLACE(LOWER(o.txid),'0x','') AND e.log_index=o.log_index AND LOWER(e.address)=LOWER(o.address) AND o.raw_amount REGEXP '^[0-9]+([.][0-9]{1,6})?$' AND CAST(o.raw_amount AS DECIMAL(18,6))=e.gross_amount AND e.block_number=o.block_number AND e.status='CREDITED'))
 + (SELECT COUNT(*) FROM nx_cregis_deposit_delivery d WHERE d.accepted=1 AND
    (d.processed_at IS NULL OR d.reason IS NULL OR d.reason NOT IN ('CREDITED','REVIEW_HOLD') OR JSON_VALID(d.raw_json)=0 OR d.txid IS NULL OR NOT (d.txid REGEXP '^(0x)?[0-9A-Fa-f]{64}$')
     OR d.signature_valid<>1 OR d.timestamp_valid<>1 OR d.ip_valid<>1 OR d.signature_valid IS NULL OR d.timestamp_valid IS NULL OR d.ip_valid IS NULL
     OR NOT EXISTS (SELECT 1 FROM nx_cregis_deposit_event e WHERE e.project_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(d.raw_json,'$.pid')) AS UNSIGNED) AND e.cid=d.cid AND REPLACE(LOWER(e.txid),'0x','')=REPLACE(LOWER(d.txid),'0x','') AND LOWER(e.address)=LOWER(d.address) AND e.gross_amount=d.gross_amount AND e.block_number=CAST(JSON_UNQUOTE(JSON_EXTRACT(d.raw_json,'$.block_height')) AS UNSIGNED) AND e.status='CREDITED')))
 + (SELECT COUNT(*) FROM nx_cregis_reconcile_watermark WHERE active_run_id IS NOT NULL)
 + (SELECT COUNT(*) FROM nx_cregis_reconcile_run WHERE status IS NULL OR status <> 'COMPLETE' OR stable_passes<2 OR completed_at IS NULL)
 + (SELECT COUNT(*) FROM nx_cregis_review_case WHERE status IS NULL OR status NOT IN ('RESOLVED','REJECTED'))
 + (SELECT COUNT(*) FROM nx_cregis_exception_claim WHERE state IS NULL OR state NOT IN ('CREDITED','REJECTED'))
 + (SELECT COUNT(*) FROM nx_cregis_exception_refund WHERE state IS NULL OR state NOT IN ('COMPLETED','REJECTED','CANCELLED','FAILED_NO_TRANSFER','CANCELLED_LATE_EVIDENCE'))
 + (SELECT COUNT(*) FROM nx_cregis_reorg_resolution WHERE status IS NULL OR status NOT IN ('RELEASED','RESTORED'))
 + (SELECT COUNT(*) FROM nx_cregis_reorg_proposal WHERE status IS NULL OR status NOT IN ('RESOLVED','REJECTED'))
 + (SELECT COUNT(*) FROM nx_cregis_ownership_challenge WHERE consumed_ref IS NULL AND expires_at>NOW()));
DROP TEMPORARY TABLE cregis_legacy_preflight;
CREATE TABLE IF NOT EXISTS nx_cregis_deposit_policy_lock (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT IGNORE INTO nx_cregis_deposit_policy_lock(id) VALUES(1);
CREATE TABLE IF NOT EXISTS nx_cregis_deposit_policy (
  version BIGINT NOT NULL PRIMARY KEY,
  min_deposit_usdt DECIMAL(18,6) NOT NULL,
  fee_usdt DECIMAL(18,6) NOT NULL,
  confirmations INT NOT NULL,
  operation_id VARCHAR(64) NULL,
  reason VARCHAR(300) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO nx_cregis_deposit_policy(version,min_deposit_usdt,fee_usdt,confirmations,reason)
SELECT 0,10,1,15,'Existing deposit policy' WHERE @cregis_policy_installed=0 AND NOT EXISTS (SELECT 1 FROM nx_cregis_deposit_policy);
CREATE TABLE IF NOT EXISTS nx_cregis_deposit_policy_snapshot (
  project_id BIGINT NOT NULL,
  chain_id VARCHAR(16) NOT NULL,
  token_id VARCHAR(64) NOT NULL,
  txid VARCHAR(66) NOT NULL,
  policy_version BIGINT NOT NULL,
  required_confirmations INT NOT NULL,
  CONSTRAINT chk_cregis_policy_snapshot CHECK (project_id > 0 AND txid REGEXP '^0x[0-9a-f]{64}$' AND required_confirmations >= 15),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(project_id,chain_id,token_id,txid),
  KEY idx_deposit_policy_snapshot_version(policy_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Only proven completed credits reach this point. Their existing wallet, ledger and order amounts are never changed.
-- Actual confirmed depth is retained conservatively; it is not represented as the historic configured minimum.
INSERT INTO nx_cregis_deposit_policy_snapshot(project_id,chain_id,token_id,txid,policy_version,required_confirmations)
SELECT e.project_id,'BSC','USDT-BEP20',CASE WHEN LOWER(e.txid) LIKE '0x%' THEN LOWER(e.txid) ELSE CONCAT('0x',LOWER(e.txid)) END,0,MAX(e.confirmations)
FROM nx_cregis_deposit_event e WHERE @cregis_policy_installed=0 GROUP BY e.project_id,CASE WHEN LOWER(e.txid) LIKE '0x%' THEN LOWER(e.txid) ELSE CONCAT('0x',LOWER(e.txid)) END
ON DUPLICATE KEY UPDATE policy_version=policy_version;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_claim' AND COLUMN_NAME='policy_version')=0,
  'ALTER TABLE nx_cregis_exception_claim ADD COLUMN policy_version BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_claim' AND COLUMN_NAME='policy_fee_usdt')=0,
  'ALTER TABLE nx_cregis_exception_claim ADD COLUMN policy_fee_usdt DECIMAL(18,6) NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
CREATE TABLE IF NOT EXISTS nx_cregis_deposit_policy_install (
  id INT NOT NULL PRIMARY KEY,
  completed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_cregis_policy_install_id CHECK (id=1)
) ENGINE=InnoDB;
INSERT IGNORE INTO nx_cregis_deposit_policy_install(id) VALUES(1);
