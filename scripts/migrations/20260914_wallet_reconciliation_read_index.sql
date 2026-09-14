-- Read-only wallet/ledger reconciliation acceleration. No wallet or ledger row is changed.
-- Revert the reader before optional rollback:
-- ALTER TABLE nx_wallet_ledger DROP INDEX idx_wallet_reconciliation_user;
SET @wallet_recon_lock_name=CONCAT('nx:wallet-recon:',LEFT(SHA2(DATABASE(),256),30));
SELECT GET_LOCK(@wallet_recon_lock_name,10) INTO @wallet_recon_lock_acquired;
SET @wallet_recon_sql=IF(@wallet_recon_lock_acquired=1,'SELECT 1','FAIL WALLET_RECON_MIGRATION_LOCK_UNAVAILABLE');
PREPARE wallet_recon_stmt FROM @wallet_recon_sql;
EXECUTE wallet_recon_stmt;
DEALLOCATE PREPARE wallet_recon_stmt;

SET @wallet_recon_source_valid=(
    SELECT COUNT(*)=5 AND SUM(
        (column_name IN ('id','user_id') AND data_type='bigint')
        OR (column_name IN ('asset','status') AND data_type='varchar' AND character_maximum_length<=128)
        OR (column_name='is_deleted' AND data_type='tinyint')
    )=5 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='nx_wallet_ledger'
      AND column_name IN ('id','user_id','asset','status','is_deleted')
) AND EXISTS(
    SELECT 1 FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='nx_wallet_ledger' AND engine='InnoDB'
);
SET @wallet_recon_sql=IF(@wallet_recon_source_valid=1,'SELECT 1','FAIL WALLET_RECON_SOURCE_SHAPE_INVALID');
PREPARE wallet_recon_stmt FROM @wallet_recon_sql;
EXECUTE wallet_recon_stmt;
DEALLOCATE PREPARE wallet_recon_stmt;

SET @wallet_recon_exists=EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='nx_wallet_ledger' AND index_name='idx_wallet_reconciliation_user'
);
SET @wallet_recon_valid=(
    SELECT COUNT(*)=5 AND SUM(
        non_unique=1 AND index_type='BTREE' AND sub_part IS NULL AND is_visible='YES' AND collation='A'
        AND ((seq_in_index=1 AND column_name='user_id')
          OR (seq_in_index=2 AND column_name='asset')
          OR (seq_in_index=3 AND column_name='status')
          OR (seq_in_index=4 AND column_name='is_deleted')
          OR (seq_in_index=5 AND column_name='id'))
    )=5 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='nx_wallet_ledger' AND index_name='idx_wallet_reconciliation_user'
);
SET @wallet_recon_sql=IF(@wallet_recon_exists=0 OR @wallet_recon_valid=1,
    'SELECT 1','FAIL WALLET_RECON_INDEX_SHAPE_INVALID');
PREPARE wallet_recon_stmt FROM @wallet_recon_sql;
EXECUTE wallet_recon_stmt;
DEALLOCATE PREPARE wallet_recon_stmt;

SET @wallet_recon_old_lock_wait=@@session.lock_wait_timeout;
SET SESSION lock_wait_timeout=15;
SET @wallet_recon_sql=IF(@wallet_recon_exists=1,'SELECT 1',
    'ALTER TABLE nx_wallet_ledger ADD INDEX idx_wallet_reconciliation_user (user_id,asset,status,is_deleted,id), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE wallet_recon_stmt FROM @wallet_recon_sql;
EXECUTE wallet_recon_stmt;
DEALLOCATE PREPARE wallet_recon_stmt;
SET SESSION lock_wait_timeout=@wallet_recon_old_lock_wait;
SELECT RELEASE_LOCK(@wallet_recon_lock_name);
