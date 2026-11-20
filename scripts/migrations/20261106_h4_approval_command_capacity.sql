-- Keep the complete original and proposed H4 rules/prizes in the existing A2 command.
-- Widen only TEXT; preserve its charset, collation, NULL semantics and exact comment.
SELECT DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_COMMENT
  INTO @h4_command_type, @h4_command_nullable, @h4_command_default,
       @h4_command_charset, @h4_command_collation, @h4_command_comment
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_audit_operation_ticket' AND COLUMN_NAME='command_json';
SET @h4_command_capacity_ddl = CASE
  WHEN @h4_command_type IN ('mediumtext','longtext') THEN 'SELECT 1'
  WHEN @h4_command_type='text' AND @h4_command_nullable='YES' AND @h4_command_default IS NULL THEN
    CONCAT('ALTER TABLE nx_audit_operation_ticket MODIFY COLUMN command_json MEDIUMTEXT CHARACTER SET ',
           @h4_command_charset, ' COLLATE ', @h4_command_collation, ' NULL COMMENT ', QUOTE(@h4_command_comment))
  ELSE 'CALL h4_approval_command_capacity_shape_invalid()'
END;
PREPARE h4_command_capacity_stmt FROM @h4_command_capacity_ddl;
EXECUTE h4_command_capacity_stmt;
DEALLOCATE PREPARE h4_command_capacity_stmt;
