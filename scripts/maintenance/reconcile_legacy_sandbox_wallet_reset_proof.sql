-- One-time forensic upgrade for a v2 retirement completed before per-wallet
-- reset items and an explicit +08:00 ledger cutoff were recorded.
--
-- This does not change any active wallet. It only succeeds when every current
-- imported balance can be reconstructed exactly from canonical ledger entries
-- after the original reset point. Otherwise it fails closed.

SET @legacy_host_offset_hours=TIMESTAMPDIFF(HOUR,UTC_TIMESTAMP(),NOW())-8;
SET time_zone='+08:00';

SET @wallet_proof_cutoff_ddl=(SELECT IF(
  EXISTS(SELECT 1 FROM information_schema.columns
          WHERE table_schema='nexion_development_archive_20260828'
            AND table_name='sandbox_retirement_wallet_reset_proof'
            AND column_name='canonical_ledger_cutoff_at'),
  'DO 0',
  'ALTER TABLE nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof ADD COLUMN canonical_ledger_cutoff_at DATETIME(6) NULL AFTER completed_at'));
PREPARE stmt FROM @wallet_proof_cutoff_ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nexion_development_archive_20260828.sandbox_retirement_wallet_reset_item (
  migration_id VARCHAR(64) NOT NULL,
  wallet_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  zero_balance_verified TINYINT(1) NOT NULL,
  proof_origin VARCHAR(48) NOT NULL,
  reset_at DATETIME(6) NOT NULL,
  reconciled_at DATETIME(6) NOT NULL,
  PRIMARY KEY (migration_id, wallet_id),
  KEY idx_wallet_reset_item_user (migration_id, user_id)
) ENGINE=InnoDB;

DROP PROCEDURE IF EXISTS nexion.reconcile_legacy_sandbox_wallet_reset_proof;
DELIMITER $$
CREATE PROCEDURE nexion.reconcile_legacy_sandbox_wallet_reset_proof()
BEGIN
  DECLARE v_migration_id VARCHAR(64) DEFAULT 'sandbox-to-development-v2-classified';
  DECLARE v_source_count BIGINT DEFAULT 0;
  DECLARE v_mismatch BIGINT DEFAULT 0;

  IF NOT EXISTS(
    SELECT 1
      FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof
     WHERE migration_id=v_migration_id AND source_count=zero_balance_count
       AND mock_amounts_reused=0
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LEGACY_WALLET_RESET_AGGREGATE_PROOF_MISSING';
  END IF;

  UPDATE nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof
     SET canonical_ledger_cutoff_at=DATE_SUB(completed_at,INTERVAL @legacy_host_offset_hours HOUR)
   WHERE migration_id=v_migration_id AND canonical_ledger_cutoff_at IS NULL;

  SELECT source_count INTO v_source_count
    FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof
   WHERE migration_id=v_migration_id;

  SELECT COUNT(*) INTO v_mismatch
    FROM nexion.nx_user_wallet w
    JOIN nexion_development_archive_20260828.nx_user_wallet a ON a.id=w.id
    JOIN nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof p
      ON p.migration_id=v_migration_id
    LEFT JOIN (
      SELECT l.user_id,
             SUM(CASE WHEN l.asset='USDT' AND l.direction='IN' THEN l.amount
                      WHEN l.asset='USDT' AND l.direction='OUT' THEN -l.amount ELSE 0 END) usdt_net,
             SUM(CASE WHEN l.asset='NEX' AND l.direction='IN' THEN l.amount
                      WHEN l.asset='NEX' AND l.direction='OUT' THEN -l.amount ELSE 0 END) nex_net,
             SUM(CASE WHEN l.biz_type='COMPUTE_TASK_REWARD' AND l.direction='IN'
                      THEN l.amount ELSE 0 END) compute_earned
        FROM nexion.nx_wallet_ledger l
        JOIN nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof p2
          ON p2.migration_id='sandbox-to-development-v2-classified'
       WHERE l.is_deleted=0 AND l.status='SUCCESS'
         AND l.created_at>=p2.canonical_ledger_cutoff_at
       GROUP BY l.user_id
    ) ledger ON ledger.user_id=w.user_id
   WHERE a.sandbox=1
     AND (w.sandbox<>0
       OR w.usdt_available<>COALESCE(ledger.usdt_net,0)
       OR w.nex_available<>COALESCE(ledger.nex_net,0)
       OR w.pending_withdraw<>0
       OR w.lifetime_earned<>COALESCE(ledger.compute_earned,0)
       OR w.cumulative_deposit_usdt<>0);
  IF v_mismatch<>0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LEGACY_WALLET_RESET_LEDGER_RECONCILIATION_FAILED';
  END IF;

  INSERT IGNORE INTO nexion_development_archive_20260828.sandbox_retirement_wallet_reset_item(
    migration_id,wallet_id,user_id,zero_balance_verified,proof_origin,reset_at,reconciled_at)
  SELECT v_migration_id,w.id,w.user_id,1,'RECONCILED_POST_RESET_LEDGER',
         p.canonical_ledger_cutoff_at,NOW(6)
    FROM nexion.nx_user_wallet w
    JOIN nexion_development_archive_20260828.nx_user_wallet a ON a.id=w.id
    JOIN nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof p
      ON p.migration_id=v_migration_id
   WHERE a.sandbox=1;

  IF (SELECT COUNT(*)
        FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_item
       WHERE migration_id=v_migration_id AND zero_balance_verified=1
         AND proof_origin='RECONCILED_POST_RESET_LEDGER')<>v_source_count THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LEGACY_WALLET_RESET_ITEM_COUNT_MISMATCH';
  END IF;
END$$
DELIMITER ;

CALL nexion.reconcile_legacy_sandbox_wallet_reset_proof();
DROP PROCEDURE IF EXISTS nexion.reconcile_legacy_sandbox_wallet_reset_proof;
