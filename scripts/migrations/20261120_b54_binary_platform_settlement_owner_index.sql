-- Keep the per-inviter check for pending earlier F3 allocations on an indexed range.
SET @b54_owner_index = IF(EXISTS(
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='nx_binary_platform_allocation'
       AND index_name='idx_binary_allocation_owner_pending'
), 'SELECT 1',
   'ALTER TABLE nx_binary_platform_allocation ADD INDEX idx_binary_allocation_owner_pending (user_id,settlement_mode,settlement_date)');
PREPARE b54_owner_index_stmt FROM @b54_owner_index;
EXECUTE b54_owner_index_stmt;
DEALLOCATE PREPARE b54_owner_index_stmt;

SET @b54_retry_reason = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='nx_binary_auto_settlement_retry'
       AND column_name='reason'
), 'SELECT 1',
   'ALTER TABLE nx_binary_auto_settlement_retry ADD COLUMN reason VARCHAR(128) NULL');
PREPARE b54_retry_reason_stmt FROM @b54_retry_reason;
EXECUTE b54_retry_reason_stmt;
DEALLOCATE PREPARE b54_retry_reason_stmt;
