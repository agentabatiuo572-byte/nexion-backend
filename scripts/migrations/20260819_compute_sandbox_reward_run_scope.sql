-- Immutable acceptance-run provenance for simulated compute rewards.
-- Historical rows cannot be attributed safely and are quarantined instead of
-- being silently assigned to whichever RunID happens to be active now.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_sandbox_reward'
    AND COLUMN_NAME = 'run_id') = 0,
  'ALTER TABLE nx_compute_sandbox_reward ADD COLUMN run_id VARCHAR(96) NULL AFTER source_environment',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_compute_sandbox_reward
   SET run_id = 'LEGACY_UNSCOPED'
 WHERE run_id IS NULL OR TRIM(run_id) = '';

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_sandbox_reward'
    AND COLUMN_NAME = 'run_id' AND COLUMN_TYPE = 'varchar(96)'
    AND IS_NULLABLE = 'NO') = 1,
  'SELECT 1',
  'ALTER TABLE nx_compute_sandbox_reward MODIFY COLUMN run_id VARCHAR(96) NOT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_shape_ok = (SELECT COUNT(*) FROM (
  SELECT INDEX_NAME, MIN(NON_UNIQUE) AS non_unique,
         GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS indexed_columns
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_sandbox_reward'
     AND INDEX_NAME = 'idx_compute_sandbox_reward_run_user_created'
   GROUP BY INDEX_NAME
) actual WHERE non_unique = 1 AND indexed_columns = 'run_id,user_id,created_at');
SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_sandbox_reward'
    AND INDEX_NAME = 'idx_compute_sandbox_reward_run_user_created');
SET @sql = IF(@index_shape_ok = 1,
  'SELECT 1',
  IF(@index_exists = 0,
    'ALTER TABLE nx_compute_sandbox_reward ADD KEY idx_compute_sandbox_reward_run_user_created (run_id, user_id, created_at)',
    'ALTER TABLE nx_compute_sandbox_reward DROP INDEX idx_compute_sandbox_reward_run_user_created, ADD KEY idx_compute_sandbox_reward_run_user_created (run_id, user_id, created_at)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
