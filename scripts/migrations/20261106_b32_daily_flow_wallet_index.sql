SET @sql = IF(EXISTS (
    SELECT 1
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'nx_wallet_ledger'
       AND index_name = 'idx_wallet_ledger_d1_flow_time'
), 'SELECT 1',
   'ALTER TABLE nx_wallet_ledger ADD INDEX idx_wallet_ledger_d1_flow_time
      (asset, direction, status, is_deleted, created_at), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
