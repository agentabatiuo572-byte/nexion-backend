-- App wallet-bill keyset read acceleration only. No balances, ledger facts, or orders are changed.
SET @wallet_bills_lock_name = CONCAT('nx:wallet-bills:', LEFT(SHA2(DATABASE(), 256), 30));
SELECT GET_LOCK(@wallet_bills_lock_name, 10) INTO @wallet_bills_lock_acquired;
SET @wallet_bills_sql = IF(@wallet_bills_lock_acquired = 1, 'SELECT 1', 'FAIL WALLET_BILLS_MIGRATION_LOCK_UNAVAILABLE');
PREPARE wallet_bills_stmt FROM @wallet_bills_sql;
EXECUTE wallet_bills_stmt;
DEALLOCATE PREPARE wallet_bills_stmt;

SET @wallet_bills_old_lock_wait = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 15;
SET @wallet_bills_index_exists = EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_wallet_ledger'
       AND index_name = 'idx_wallet_ledger_user_deleted_cursor'
);
SET @wallet_bills_sql = IF(@wallet_bills_index_exists = 0,
    'ALTER TABLE nx_wallet_ledger ADD INDEX idx_wallet_ledger_user_deleted_cursor (user_id, is_deleted, created_at DESC, id DESC), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1');
PREPARE wallet_bills_stmt FROM @wallet_bills_sql;
EXECUTE wallet_bills_stmt;
DEALLOCATE PREPARE wallet_bills_stmt;

SET @wallet_bills_index_valid = (
    SELECT COUNT(*) = 4 AND COALESCE(SUM(
        non_unique = 1 AND index_type = 'BTREE' AND sub_part IS NULL AND is_visible = 'YES'
        AND ((seq_in_index = 1 AND column_name = 'user_id' AND collation = 'A')
          OR (seq_in_index = 2 AND column_name = 'is_deleted' AND collation = 'A')
          OR (seq_in_index = 3 AND column_name = 'created_at' AND collation = 'D')
          OR (seq_in_index = 4 AND column_name = 'id' AND collation = 'D'))
    ), 0) = 4
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_wallet_ledger'
       AND index_name = 'idx_wallet_ledger_user_deleted_cursor'
);
SET @wallet_bills_sql = IF(@wallet_bills_index_valid = 1, 'SELECT 1',
    'FAIL WALLET_BILLS_INDEX_SHAPE_INVALID_idx_wallet_ledger_user_deleted_cursor');
PREPARE wallet_bills_stmt FROM @wallet_bills_sql;
EXECUTE wallet_bills_stmt;
DEALLOCATE PREPARE wallet_bills_stmt;

SET SESSION lock_wait_timeout = @wallet_bills_old_lock_wait;
SELECT RELEASE_LOCK(@wallet_bills_lock_name);
