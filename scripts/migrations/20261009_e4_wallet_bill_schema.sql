-- Existing E4 wallet refunds already require this bill projection alongside nx_wallet_ledger.
-- Structure only: no balances, bills, orders, historical rows, or attribution are inserted/backfilled.
-- An existing incompatible table is rejected before CREATE; it is never altered or rewritten.
-- Checks remain in the existing E4 writer: this table does not invent general bill type/amount rules.
-- Existing wider DECIMAL columns preserve the writer's 18,6 amounts without narrowing historical storage.
SET @e4_bill_validate_sql = 'SELECT (
 NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=''nx_wallet_bill'')
 OR (
  EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=''nx_wallet_bill''
         AND table_type=''BASE TABLE'' AND engine=''InnoDB'')
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema=DATABASE() AND table_name=''nx_wallet_bill'' AND is_nullable=''NO''
         AND COALESCE(generation_expression,'''')=''''
         AND (
          (column_name=''id'' AND data_type=''bigint'' AND column_type LIKE ''%unsigned%'')
          OR (column_name=''user_id'' AND data_type=''bigint'')
          OR (column_name=''bill_no'' AND data_type=''varchar'' AND character_maximum_length>=104 AND character_set_name=''utf8mb4'')
          OR (column_name=''type'' AND data_type=''varchar'' AND character_maximum_length>=12)
          OR (column_name=''token'' AND data_type=''varchar'' AND character_maximum_length>=4)
          OR (column_name=''amount'' AND data_type=''decimal'' AND numeric_precision>=18 AND numeric_scale=6)
          OR (column_name=''direction'' AND data_type=''varchar'' AND character_maximum_length>=2)
          OR (column_name IN (''occurred_at'',''created_at'') AND data_type=''datetime'')
          OR (column_name=''deleted'' AND data_type=''tinyint'')
         ))=10
  AND NOT EXISTS(SELECT 1 FROM information_schema.columns
       WHERE table_schema=DATABASE() AND table_name=''nx_wallet_bill''
         AND column_name NOT IN (''id'',''user_id'',''bill_no'',''type'',''token'',''amount'',''direction'',''occurred_at'',''created_at'',''deleted'')
         AND is_nullable=''NO'' AND column_default IS NULL AND COALESCE(generation_expression,'''')=''''
         AND extra NOT LIKE ''%auto_increment%'')
  AND (SELECT COUNT(*)=1 AND COALESCE(SUM(column_name=''id'' AND seq_in_index=1 AND sub_part IS NULL AND is_visible=''YES''),0)=1
       FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name=''nx_wallet_bill'' AND index_name=''PRIMARY'')=1
  AND EXISTS(SELECT index_name FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name=''nx_wallet_bill'' AND non_unique=0
       GROUP BY index_name
       HAVING COUNT(*)=1 AND SUM(column_name=''bill_no'' AND seq_in_index=1 AND sub_part IS NULL AND is_visible=''YES'')=1)
 )
) INTO @e4_bill_schema_compatible';
PREPARE e4_bill_schema_check FROM @e4_bill_validate_sql;
EXECUTE e4_bill_schema_check;
DEALLOCATE PREPARE e4_bill_schema_check;
SET @e4_bill_guard_sql = IF(@e4_bill_schema_compatible=1,
 'SELECT 1', 'FAIL E4_WALLET_BILL_SCHEMA_INCOMPATIBLE');
PREPARE e4_bill_schema_guard FROM @e4_bill_guard_sql;
EXECUTE e4_bill_schema_guard;
DEALLOCATE PREPARE e4_bill_schema_guard;

CREATE TABLE IF NOT EXISTS nx_wallet_bill (
 id BIGINT UNSIGNED NOT NULL,
 user_id BIGINT NOT NULL,
 bill_no VARCHAR(128) NOT NULL,
 type VARCHAR(32) NOT NULL,
 token VARCHAR(16) NOT NULL,
 amount DECIMAL(18,6) NOT NULL,
 direction VARCHAR(8) NOT NULL,
 occurred_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY (id),
 UNIQUE KEY uk_wallet_bill_no (bill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reuse the exact preflight after CREATE to catch a concurrent incompatible table creation too.
PREPARE e4_bill_schema_check FROM @e4_bill_validate_sql;
EXECUTE e4_bill_schema_check;
DEALLOCATE PREPARE e4_bill_schema_check;
SET @e4_bill_guard_sql = IF(@e4_bill_schema_compatible=1,
 'SELECT 1', 'FAIL E4_WALLET_BILL_SCHEMA_INCOMPATIBLE');
PREPARE e4_bill_schema_guard FROM @e4_bill_guard_sql;
EXECUTE e4_bill_schema_guard;
DEALLOCATE PREPARE e4_bill_schema_guard;
