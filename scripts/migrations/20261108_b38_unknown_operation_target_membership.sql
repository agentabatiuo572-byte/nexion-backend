-- B38-BE-03: each batch member fences the same object in later single or overlapping batch commands.
-- Only server-captured origins can be backfilled; historical unproven commands stay unproven.
SET @b38_membership_ddl = IF(EXISTS(
 SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_idempotency_record' AND COLUMN_NAME='origin_targets'
), 'SELECT 1', 'ALTER TABLE nx_admin_idempotency_record ADD COLUMN origin_targets JSON NULL');
PREPARE b38_membership_stmt FROM @b38_membership_ddl;
EXECUTE b38_membership_stmt;
DEALLOCATE PREPARE b38_membership_stmt;

SET @b38_membership_ddl = IF(EXISTS(
 SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_command_investigation' AND COLUMN_NAME='original_targets'
), 'SELECT 1', 'ALTER TABLE nx_admin_command_investigation ADD COLUMN original_targets JSON NULL');
PREPARE b38_membership_stmt FROM @b38_membership_ddl;
EXECUTE b38_membership_stmt;
DEALLOCATE PREPARE b38_membership_stmt;

UPDATE nx_admin_idempotency_record
 SET origin_targets=JSON_ARRAY(JSON_OBJECT('action',scope,'domain',origin_read_authority,'type','business_target','id',origin_target))
 WHERE origin_targets IS NULL AND origin_admin_id IS NOT NULL AND origin_read_authority IS NOT NULL
   AND origin_target IS NOT NULL AND is_deleted=0;
