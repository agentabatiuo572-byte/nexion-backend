-- Additive only: legacy genders and all existing avatar references remain untouched.
DELIMITER $$
DROP PROCEDURE IF EXISTS support_default_avatar_migrate$$
CREATE PROCEDURE support_default_avatar_migrate()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_admin_account_state' AND column_name='avatar_gender') THEN
  ALTER TABLE nx_admin_account_state ADD COLUMN avatar_gender VARCHAR(16) NULL;
 END IF;
END$$
CALL support_default_avatar_migrate()$$
DROP PROCEDURE support_default_avatar_migrate$$
DELIMITER ;
