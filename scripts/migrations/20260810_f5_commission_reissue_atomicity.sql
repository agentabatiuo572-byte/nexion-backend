-- F5 reissue is a consume-once correction. The generated nullable key scopes
-- uniqueness to successful REISSUE operations without changing reverse,
-- suspension or automatic-unlock operation semantics.
SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE()
    AND table_name='nx_commission_operation'
    AND column_name='reissue_source_commission_id')=0,
  'ALTER TABLE nx_commission_operation ADD COLUMN reissue_source_commission_id BIGINT GENERATED ALWAYS AS (CASE WHEN operation_type = ''REISSUE'' THEN source_commission_id ELSE NULL END) STORED AFTER source_commission_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE()
    AND table_name='nx_commission_operation'
    AND index_name='uk_commission_reissue_source')=0,
  'ALTER TABLE nx_commission_operation ADD UNIQUE KEY uk_commission_reissue_source (reissue_source_commission_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
