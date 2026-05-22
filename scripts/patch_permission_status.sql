USE nexion;

SET @has_status = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'admin_permission'
    AND column_name = 'status'
);

SET @sql = IF(@has_status = 0,
  'ALTER TABLE admin_permission ADD COLUMN status TINYINT NOT NULL DEFAULT 1 AFTER remark',
  'SELECT 1'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE admin_permission SET status = 1 WHERE status IS NULL;
