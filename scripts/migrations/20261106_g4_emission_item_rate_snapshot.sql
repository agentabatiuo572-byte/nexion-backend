-- Old batches keep their original rate. New batches freeze a rate on each
-- item and use NULL for the inapplicable batch-wide rate.
SET @g4_item_rate_ddl = IF(EXISTS(
  SELECT 1 FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_genesis_emission_item' AND COLUMN_NAME='rate_applied_pct'
), 'SELECT 1', 'ALTER TABLE nx_genesis_emission_item ADD COLUMN rate_applied_pct DECIMAL(10,6) NULL DEFAULT NULL AFTER amount_usdt');
PREPARE g4_item_rate_stmt FROM @g4_item_rate_ddl;
EXECUTE g4_item_rate_stmt;
DEALLOCATE PREPARE g4_item_rate_stmt;

SET @g4_batch_rate_ddl = IF(EXISTS(
  SELECT 1 FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_genesis_emission_batch' AND COLUMN_NAME='daily_rate_pct' AND IS_NULLABLE='NO'
), 'ALTER TABLE nx_genesis_emission_batch MODIFY COLUMN daily_rate_pct DECIMAL(10,6) NULL DEFAULT NULL', 'SELECT 1');
PREPARE g4_batch_rate_stmt FROM @g4_batch_rate_ddl;
EXECUTE g4_batch_rate_stmt;
DEALLOCATE PREPARE g4_batch_rate_stmt;
