USE nexion;

SET @has_menu_name_zh = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'admin_menu'
    AND column_name = 'menu_name_zh'
);

SET @sql = IF(@has_menu_name_zh = 0,
  'ALTER TABLE admin_menu ADD COLUMN menu_name_zh VARCHAR(96) NULL AFTER menu_name',
  'SELECT 1'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_menu_name_en = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'admin_menu'
    AND column_name = 'menu_name_en'
);

SET @sql = IF(@has_menu_name_en = 0,
  'ALTER TABLE admin_menu ADD COLUMN menu_name_en VARCHAR(96) NULL AFTER menu_name_zh',
  'SELECT 1'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE admin_menu
SET menu_name_zh = menu_name
WHERE menu_name_zh IS NULL OR menu_name_zh = '';

UPDATE admin_menu
SET menu_name_en = menu_name
WHERE menu_name_en IS NULL OR menu_name_en = '';
