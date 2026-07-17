-- K3 withdrawal-rule closure: deterministic priority and optimistic concurrency.
-- Keep this script replay-safe for local recovery and repeated environment rollout.
SET @k3_has_priority = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_admin_risk_withdraw_rule'
     AND COLUMN_NAME = 'priority'
);
SET @k3_sql = IF(@k3_has_priority = 0,
  'ALTER TABLE nx_admin_risk_withdraw_rule ADD COLUMN priority INT NOT NULL DEFAULT 50 AFTER built_in',
  'SELECT 1');
PREPARE k3_stmt FROM @k3_sql;
EXECUTE k3_stmt;
DEALLOCATE PREPARE k3_stmt;

SET @k3_has_version = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_admin_risk_withdraw_rule'
     AND COLUMN_NAME = 'version'
);
SET @k3_sql = IF(@k3_has_version = 0,
  'ALTER TABLE nx_admin_risk_withdraw_rule ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER priority',
  'SELECT 1');
PREPARE k3_stmt FROM @k3_sql;
EXECUTE k3_stmt;
DEALLOCATE PREPARE k3_stmt;

-- Archived is a terminal state and must remain separated from ordinary toggles.
UPDATE nx_admin_permission
   SET permission_name = '归档提现规则',
       perm_type = 'WRITE',
       amplifies = 1
 WHERE permission_code = 'risk_k3_rule_archive';
