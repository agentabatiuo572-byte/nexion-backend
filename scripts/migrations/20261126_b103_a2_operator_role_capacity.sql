-- Store the complete authenticated role label on new delegated A2 tickets.
SET @b103_operator_role_ddl = COALESCE((
  SELECT CASE
    WHEN CHARACTER_MAXIMUM_LENGTH >= 80 THEN 'SELECT 1'
    WHEN DATA_TYPE = 'varchar' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL THEN
      CONCAT('ALTER TABLE nx_audit_operation_ticket MODIFY COLUMN operator_role VARCHAR(80) CHARACTER SET ',
             CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME, ' NOT NULL COMMENT ', QUOTE(COLUMN_COMMENT))
    ELSE NULL
  END
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_audit_operation_ticket' AND COLUMN_NAME = 'operator_role'
), 'CALL b103_operator_role_capacity_shape_invalid()');
PREPARE b103_operator_role_stmt FROM @b103_operator_role_ddl;
EXECUTE b103_operator_role_stmt;
DEALLOCATE PREPARE b103_operator_role_stmt;
