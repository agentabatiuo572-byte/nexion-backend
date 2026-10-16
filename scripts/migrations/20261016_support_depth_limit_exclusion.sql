-- Preserve the original advisor only for new depth-limit decisions.
-- Existing pool eligibility, assignments and historical identities are not inferred or rewritten.
SET @support_exclusion_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_binding_pool' AND COLUMN_NAME='excluded_agent_admin_id')=0,
  'ALTER TABLE nx_support_binding_pool ADD COLUMN excluded_agent_admin_id BIGINT NULL', 'SELECT 1');
PREPARE support_exclusion_stmt FROM @support_exclusion_ddl;
EXECUTE support_exclusion_stmt;
DEALLOCATE PREPARE support_exclusion_stmt;
