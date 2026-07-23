-- A2 security closure: row scope, independent export permission and least-privilege role grants.
SET @schema_name = DATABASE();
SET @add_source_domain = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE nx_audit_operation_ticket ADD COLUMN source_domain VARCHAR(8) NOT NULL DEFAULT ''A'' AFTER command_json, ADD KEY idx_audit_operation_ticket_domain (source_domain, status, created_at)',
    'SELECT 1')
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'nx_audit_operation_ticket' AND COLUMN_NAME = 'source_domain');
PREPARE stmt FROM @add_source_domain; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Repair a partially applied migration where the column exists but its lookup index does not.
SET @add_source_domain_index = (
  SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_audit_operation_ticket_domain ON nx_audit_operation_ticket (source_domain, status, created_at)',
    'SELECT 1')
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'nx_audit_operation_ticket'
   AND INDEX_NAME = 'idx_audit_operation_ticket_domain');
PREPARE stmt FROM @add_source_domain_index; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Prefer the replay command's canonical domain. Legacy rows without one are inferred conservatively;
-- genuinely unclassifiable rows remain A and are therefore hidden from scoped non-A roles.
UPDATE nx_audit_operation_ticket
   SET source_domain = CASE
         WHEN LEFT(UPPER(COALESCE(source_domain,'')),1) REGEXP '^[B-M]$'
           THEN LEFT(UPPER(source_domain),1)
         WHEN LEFT(UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(command_json, '$.domain')),'')),1) REGEXP '^[A-M]$'
           THEN LEFT(UPPER(JSON_UNQUOTE(JSON_EXTRACT(command_json, '$.domain'))),1)
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])M[0-9]' THEN 'M'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])L[0-9]' THEN 'L'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])K[0-9]' THEN 'K'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])J[0-9]' THEN 'J'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])I[0-9]' THEN 'I'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])H[0-9]' THEN 'H'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])G[0-9]' THEN 'G'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])F[0-9]' THEN 'F'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])E[0-9]' THEN 'E'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])D[0-9]' THEN 'D'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])C[0-9]' THEN 'C'
         WHEN UPPER(CONCAT(action, ' ', object_text)) REGEXP '(^|[^A-Z])B[0-9]' THEN 'B'
         ELSE 'A' END;

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES ('platform_a2_export', 'A2 审计日志导出', 'API', '/platform/audit/exports', 'READ', 0, 1, 0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), resource_type=VALUES(resource_type),
 resource_path=VALUES(resource_path), perm_type=VALUES(perm_type), status=1, is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
  ON p.permission_code = 'platform_a2_read'
 WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','RISK','GROWTH','CONTENT','SUPPORT','AUDITOR')
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
  ON p.permission_code = 'platform_a2_export'
 WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','RISK','AUDITOR')
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code IN ('platform_a2_write','platform_a2_operation_approve') AND r.role_code <> 'SUPER_ADMIN';
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='platform_a2_export' AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE','RISK','AUDITOR');

-- This release also bumps the application permission-cache namespace to
-- rbac:v2:admin:perms:* so stale pre-migration grants are never read after deployment.
