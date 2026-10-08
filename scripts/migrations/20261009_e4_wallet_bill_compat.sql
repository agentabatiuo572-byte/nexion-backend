-- Upgrade only the recognized E4 legacy capacity layout before the unchanged strict schema guard.
-- No bills, balances, ledger facts, orders or identities are inserted, deleted or backfilled.
-- One atomic MySQL 8 DDL changes both capacities; unknown partial upgrades are rejected.
-- Cooperating runs share this lock. On any SQL failure, close the connection to release it.
SET @e4_bill_compat_locked=0, @e4_bill_legacy=0, @e4_bill_capacity_ready=0,
 @e4_bill_fk_safe=0, @e4_bill_trigger_safe=0, @e4_bill_constraint_safe=0,
 @e4_bill_mode_safe=0, @e4_bill_ids_safe=0;
SET @e4_bill_compat_lock = CONCAT('nx:e4-bill-compat:', LEFT(SHA2(DATABASE(), 256), 36));
SELECT GET_LOCK(@e4_bill_compat_lock, 10) INTO @e4_bill_compat_locked;
SET @e4_bill_compat_sql = IF(@e4_bill_compat_locked=1,
 'SELECT 1', 'FAIL E4_WALLET_BILL_COMPAT_LOCK_UNAVAILABLE');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;
SET @e4_bill_compat_old_lock_wait = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 15;

SET @e4_bill_exists = EXISTS(SELECT 1 FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill');
SET @e4_bill_capacity_ready = (
 SELECT COUNT(*)=2 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill'
   AND is_nullable='NO' AND COALESCE(generation_expression,'')=''
   AND ((column_name='id' AND data_type='bigint' AND column_type LIKE '%unsigned%')
     OR (column_name='bill_no' AND data_type='varchar' AND character_maximum_length>=104
         AND character_set_name='utf8mb4'))
);
-- Capacity-ready is only a no-op decision; the following strict migration validates the whole schema.
SET @e4_bill_legacy = (
 EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()
  AND table_name='nx_wallet_bill' AND table_type='BASE TABLE' AND engine='InnoDB')
 AND (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill')=10
 AND (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill' AND is_nullable='NO'
        AND COALESCE(generation_expression,'')=''
        AND (
         (column_name='id' AND column_type='bigint' AND column_default IS NULL AND extra='')
         OR (column_name='user_id' AND column_type='bigint' AND column_default IS NULL AND extra='')
         OR (column_name='bill_no' AND data_type='varchar' AND character_maximum_length=64
             AND character_set_name='utf8mb4' AND collation_name REGEXP '^utf8mb4_[a-z0-9_]+$'
             AND column_default IS NULL AND extra='')
         OR (column_name='type' AND data_type='varchar' AND character_maximum_length=64
             AND character_set_name='utf8mb4' AND column_default IS NULL AND extra='')
         OR (column_name='token' AND data_type='varchar' AND character_maximum_length=16
             AND character_set_name='utf8mb4' AND column_default IS NULL AND extra='')
         OR (column_name='amount' AND data_type='decimal' AND numeric_precision=18 AND numeric_scale=6
             AND column_default IS NULL AND extra='')
         OR (column_name='direction' AND data_type='varchar' AND character_maximum_length=8
             AND character_set_name='utf8mb4' AND column_default IS NULL AND extra='')
         OR (column_name='occurred_at' AND data_type='datetime' AND column_default IS NULL AND extra='')
         OR (column_name='created_at' AND data_type='datetime' AND column_default='CURRENT_TIMESTAMP'
             AND extra='DEFAULT_GENERATED')
         OR (column_name='deleted' AND data_type='tinyint' AND column_default='0' AND extra='')
        ))=10
 AND (SELECT COUNT(*)=1 AND COALESCE(SUM(column_name='id' AND seq_in_index=1
       AND sub_part IS NULL AND is_visible='YES'),0)=1 FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill' AND index_name='PRIMARY')=1
 AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill' AND non_unique=0)=2
 AND EXISTS(SELECT index_name FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill' AND non_unique=0 AND index_name<>'PRIMARY'
      GROUP BY index_name HAVING COUNT(*)=1 AND SUM(column_name='bill_no' AND seq_in_index=1
       AND sub_part IS NULL AND is_visible='YES')=1)
);
SET @e4_bill_compat_sql = IF(NOT @e4_bill_exists OR @e4_bill_capacity_ready OR @e4_bill_legacy,
 'SELECT 1', 'FAIL E4_WALLET_BILL_LEGACY_INCOMPATIBLE');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;

SET @e4_bill_fk_safe = NOT EXISTS(SELECT 1 FROM information_schema.key_column_usage
 WHERE (table_schema=DATABASE() AND table_name='nx_wallet_bill' AND referenced_table_name IS NOT NULL)
    OR (referenced_table_schema=DATABASE() AND referenced_table_name='nx_wallet_bill'));
SET @e4_bill_compat_sql = IF(@e4_bill_fk_safe=1,
 'SELECT 1', 'FAIL E4_WALLET_BILL_FOREIGN_KEY');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;
SET @e4_bill_trigger_safe = NOT EXISTS(SELECT 1 FROM information_schema.triggers
 WHERE trigger_schema=DATABASE() AND event_object_table='nx_wallet_bill');
SET @e4_bill_compat_sql = IF(@e4_bill_trigger_safe=1,
 'SELECT 1', 'FAIL E4_WALLET_BILL_TRIGGER');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;
SET @e4_bill_constraint_safe = NOT EXISTS(SELECT 1 FROM information_schema.table_constraints
 WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill'
   AND constraint_type NOT IN ('PRIMARY KEY','UNIQUE'));
SET @e4_bill_compat_sql = IF(@e4_bill_constraint_safe=1,
 'SELECT 1', 'FAIL E4_WALLET_BILL_CONSTRAINT');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;

-- Strict conversion prevents a concurrent negative-ID insert from being silently coerced.
-- QUOTE preserves original comments; reject NO_BACKSLASH_ESCAPES rather than change SQL mode.
SET @e4_bill_mode_safe = (
 (FIND_IN_SET('STRICT_TRANS_TABLES',@@session.sql_mode)>0
  OR FIND_IN_SET('STRICT_ALL_TABLES',@@session.sql_mode)>0)
 AND FIND_IN_SET('NO_BACKSLASH_ESCAPES',@@session.sql_mode)=0);
SET @e4_bill_compat_sql = IF(NOT @e4_bill_legacy OR @e4_bill_mode_safe=1,
 'SELECT 1', 'FAIL E4_WALLET_BILL_SQL_MODE');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;
SET @e4_bill_compat_sql = IF(@e4_bill_legacy,
 'SELECT NOT EXISTS(SELECT 1 FROM nx_wallet_bill WHERE id<0) INTO @e4_bill_ids_safe',
 'SET @e4_bill_ids_safe=1');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;
SET @e4_bill_compat_sql = IF(@e4_bill_ids_safe=1,
 'SELECT 1', 'FAIL E4_WALLET_BILL_NEGATIVE_ID');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;

SELECT MAX(IF(column_name='id',column_comment,NULL)),
       MAX(IF(column_name='bill_no',column_comment,NULL)),
       MAX(IF(column_name='bill_no',collation_name,NULL))
 INTO @e4_bill_id_comment,@e4_bill_no_comment,@e4_bill_no_collation
 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill';
-- Also bind DDL to every guard result if a client's source mode continues after an error.
SET @e4_bill_compat_sql = IF(@e4_bill_compat_locked=1 AND @e4_bill_legacy=1
 AND @e4_bill_fk_safe=1 AND @e4_bill_trigger_safe=1 AND @e4_bill_constraint_safe=1
 AND @e4_bill_mode_safe=1 AND @e4_bill_ids_safe=1, CONCAT(
 'ALTER TABLE nx_wallet_bill MODIFY COLUMN id BIGINT UNSIGNED NOT NULL COMMENT ',QUOTE(@e4_bill_id_comment),
 ', MODIFY COLUMN bill_no VARCHAR(128) CHARACTER SET utf8mb4 COLLATE ',@e4_bill_no_collation,
 ' NOT NULL COMMENT ',QUOTE(@e4_bill_no_comment)), 'SELECT 1');
PREPARE e4_bill_compat_stmt FROM @e4_bill_compat_sql;
EXECUTE e4_bill_compat_stmt;
DEALLOCATE PREPARE e4_bill_compat_stmt;
-- MySQL 8 commits this one DDL atomically. A failed connection must be closed; do not continue the chain.
-- Noncooperating external DDL is outside the named-lock protocol, not a supported retry case.
SET SESSION lock_wait_timeout = @e4_bill_compat_old_lock_wait;
SELECT RELEASE_LOCK(@e4_bill_compat_lock);
