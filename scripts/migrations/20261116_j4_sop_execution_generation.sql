-- Add the execution generation used as the J4 progress-write fence.
-- Existing rows start at 0; a recovery claim increments the generation before
-- reconciliation, so writes carrying the old generation cannot overwrite the
-- recovery result. No existing status, JSON, timestamp or audit data is rewritten.
SET @j4_execution_generation_sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_emergency_sop_execution'
      AND COLUMN_NAME = 'execution_generation') = 0,
  'ALTER TABLE nx_emergency_sop_execution
     ADD COLUMN execution_generation BIGINT NOT NULL DEFAULT 0 AFTER execution_mode',
  'SELECT 1');
PREPARE j4_execution_generation_stmt FROM @j4_execution_generation_sql;
EXECUTE j4_execution_generation_stmt;
DEALLOCATE PREPARE j4_execution_generation_stmt;
