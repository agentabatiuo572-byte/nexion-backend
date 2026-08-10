-- K6: authoritative device evidence required for success/reconciliation.
SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_janus_takeover_execution' AND column_name='actual_target_version')=0,
  'ALTER TABLE nx_janus_takeover_execution ADD COLUMN actual_target_version INT NULL AFTER actual_target_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_janus_takeover_execution' AND column_name='actual_target_catalog_version')=0,
  'ALTER TABLE nx_janus_takeover_execution ADD COLUMN actual_target_catalog_version BIGINT NULL AFTER actual_target_version',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_janus_takeover_execution' AND column_name='handoff_receipt')=0,
  'ALTER TABLE nx_janus_takeover_execution ADD COLUMN handoff_receipt VARCHAR(256) NULL AFTER device_app_version',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
