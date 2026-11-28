-- Record the placement at delivery, independently of later lifecycle commands.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_device'
                  AND COLUMN_NAME='activated_on_delivery') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN activated_on_delivery BOOLEAN NULL AFTER activated_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
