-- Locate the original two-asset recovery predicate within its environment/run and id cursor.
-- Functional key parts use MySQL-hidden virtual columns; visible business columns stay unchanged.
-- Rollback: ALTER TABLE nx_direct_referral_settlement DROP INDEX idx_direct_pending_recovery_typed, ALGORITHM=INPLACE, LOCK=SHARED;
SET @direct_pending_recovery_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_direct_referral_settlement'
       AND index_name = 'idx_direct_pending_recovery_typed'
);
SET @direct_pending_recovery_sql = IF(@direct_pending_recovery_index_exists = 0,
    'ALTER TABLE nx_direct_referral_settlement ADD INDEX idx_direct_pending_recovery_typed (source_environment,run_id,((recovery_pending_usdt>0.000000 OR recovery_pending_nex>0.000000)),id), ALGORITHM=INPLACE, LOCK=SHARED',
    'SELECT 1');
PREPARE direct_pending_recovery_stmt FROM @direct_pending_recovery_sql;
EXECUTE direct_pending_recovery_stmt;
DEALLOCATE PREPARE direct_pending_recovery_stmt;

SET @direct_pending_recovery_index_valid = (
    SELECT COUNT(*) = 4 AND COALESCE(SUM(
        non_unique = 1 AND sub_part IS NULL AND is_visible = 'YES'
        AND index_type = 'BTREE' AND collation = 'A' AND (
          (seq_in_index = 1 AND column_name = 'source_environment' AND expression IS NULL)
          OR (seq_in_index = 2 AND column_name = 'run_id' AND expression IS NULL)
          OR (seq_in_index = 3 AND column_name IS NULL
              AND LOWER(REPLACE(REPLACE(expression, CHAR(96), ''), ' ', ''))
                  = '((recovery_pending_usdt>0.000000)or(recovery_pending_nex>0.000000))')
          OR (seq_in_index = 4 AND column_name = 'id' AND expression IS NULL)
        )), 0) = 4
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'nx_direct_referral_settlement'
       AND index_name = 'idx_direct_pending_recovery_typed'
);
SET @direct_pending_recovery_sql = IF(@direct_pending_recovery_index_valid = 1,
    'SELECT 1', 'FAIL DIRECT_PENDING_RECOVERY_INDEX_SHAPE_INVALID');
PREPARE direct_pending_recovery_stmt FROM @direct_pending_recovery_sql;
EXECUTE direct_pending_recovery_stmt;
DEALLOCATE PREPARE direct_pending_recovery_stmt;
