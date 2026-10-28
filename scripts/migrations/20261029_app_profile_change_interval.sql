-- App 资料修改间隔：用户名和头像各自每 7 天可修改一次。记录最近一次由用户本人修改的时间（存储时区），
-- 注册时系统分配的用户名和默认头像不算修改，列保持 NULL，首次修改不受限制。
DROP PROCEDURE IF EXISTS add_app_profile_change_columns;
DELIMITER $$
CREATE PROCEDURE add_app_profile_change_columns()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user' AND COLUMN_NAME='nickname_changed_at'
  ) THEN
    ALTER TABLE nx_user ADD COLUMN nickname_changed_at DATETIME NULL;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user' AND COLUMN_NAME='avatar_changed_at'
  ) THEN
    ALTER TABLE nx_user ADD COLUMN avatar_changed_at DATETIME NULL;
  END IF;
END$$
DELIMITER ;

CALL add_app_profile_change_columns();
DROP PROCEDURE add_app_profile_change_columns;
