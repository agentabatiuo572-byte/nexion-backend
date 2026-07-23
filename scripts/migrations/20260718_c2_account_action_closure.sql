-- C2 account-action closure: reversible D2 freeze linkage and exact C2 role bindings.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
                 AND COLUMN_NAME='c2_previous_status')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN c2_previous_status VARCHAR(32) NULL AFTER failure_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user'
                 AND COLUMN_NAME='c2_freeze_source')=0,
  'ALTER TABLE nx_user ADD COLUMN c2_freeze_source VARCHAR(64) NULL AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user'
                 AND COLUMN_NAME='c2_freeze_source_ref')=0,
  'ALTER TABLE nx_user ADD COLUMN c2_freeze_source_ref VARCHAR(128) NULL AFTER c2_freeze_source',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user'
                 AND COLUMN_NAME='c2_freeze_reason')=0,
  'ALTER TABLE nx_user ADD COLUMN c2_freeze_reason VARCHAR(500) NULL AFTER c2_freeze_source_ref',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user'
                 AND COLUMN_NAME='c2_freeze_operator')=0,
  'ALTER TABLE nx_user ADD COLUMN c2_freeze_operator VARCHAR(128) NULL AFTER c2_freeze_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user'
                 AND COLUMN_NAME='c2_frozen_at')=0,
  'ALTER TABLE nx_user ADD COLUMN c2_frozen_at DATETIME NULL AFTER c2_freeze_operator',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
                 AND COLUMN_NAME='c2_frozen_by_user_status')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN c2_frozen_by_user_status TINYINT NOT NULL DEFAULT 0 AFTER c2_previous_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- C2 view: SUPER_ADMIN / FINANCE / RISK / SUPPORT / AUDITOR.
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code IN ('SUPER_ADMIN','FINANCE','RISK','SUPPORT','AUDITOR')
JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.permission_code='user_c2_read'
SET rp.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','RISK','SUPPORT','AUDITOR')
  AND p.permission_code='user_c2_read' AND p.status=1 AND p.is_deleted=0;

-- FINANCE/RISK/SUPPORT may force logout; SUPPORT/RISK may start or terminate read-only impersonation.
-- FINANCE has freeze/unfreeze business scope only for A2 proposal submission; it has no A2 approval permission.
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=0
WHERE (r.role_code IN ('SUPER_ADMIN','FINANCE','RISK','SUPPORT') AND p.permission_code='user_c2_session_revoke_all')
   OR (r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT') AND p.permission_code IN ('user_c2_impersonate_start','user_c2_impersonate_terminate'))
   OR (r.role_code IN ('SUPER_ADMIN','RISK','FINANCE') AND p.permission_code IN ('user_c2_account_freeze','user_c2_account_unfreeze'))
   OR (r.role_code IN ('SUPER_ADMIN','RISK') AND p.permission_code='user_c2_blocklist_add')
   OR (r.role_code='FINANCE' AND p.permission_code='platform_a2_proposal_create');
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE ((r.role_code IN ('SUPER_ADMIN','FINANCE','RISK','SUPPORT') AND p.permission_code='user_c2_session_revoke_all')
    OR (r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT') AND p.permission_code IN ('user_c2_impersonate_start','user_c2_impersonate_terminate'))
    OR (r.role_code IN ('SUPER_ADMIN','RISK','FINANCE') AND p.permission_code IN ('user_c2_account_freeze','user_c2_account_unfreeze'))
    OR (r.role_code IN ('SUPER_ADMIN','RISK') AND p.permission_code='user_c2_blocklist_add')
    OR (r.role_code='FINANCE' AND p.permission_code='platform_a2_proposal_create'))
  AND p.status=1 AND p.is_deleted=0;

-- Remove C2 mutation permissions from roles not listed by the PRD matrix.
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1
WHERE (p.permission_code IN ('user_c2_account_freeze','user_c2_account_unfreeze','user_c2_blocklist_add')
       AND ((p.permission_code IN ('user_c2_account_freeze','user_c2_account_unfreeze') AND r.role_code NOT IN ('SUPER_ADMIN','RISK','FINANCE'))
         OR (p.permission_code='user_c2_blocklist_add' AND r.role_code NOT IN ('SUPER_ADMIN','RISK'))))
   OR (p.permission_code IN ('user_c2_impersonate_start','user_c2_impersonate_terminate')
       AND r.role_code NOT IN ('SUPER_ADMIN','RISK','SUPPORT'))
   OR (p.permission_code='user_c2_session_revoke_all'
       AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE','RISK','SUPPORT'));

-- Canonical A4 contracts for every C2 high-risk account or impersonation state transition.
INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('admin.user_frozen', 'admin', 'phase_admin', 'server', 'A2/B5/C2/c2-high-risk-admin-alert', 1, '100%', 25, 'ACTIVE', 'migration:c2', 'C2 authoritative account freeze event', 0),
  ('admin.user_unfrozen', 'admin', 'phase_admin', 'server', 'A2/B5/C2/c2-high-risk-admin-alert', 1, '100%', 26, 'ACTIVE', 'migration:c2', 'C2 authoritative account recovery event', 0),
  ('admin.user_impersonation_started', 'admin', 'phase_admin', 'server', 'A2/B5/C2/c2-high-risk-admin-alert', 1, '100%', 27, 'ACTIVE', 'migration:c2', 'C2 authoritative read-only impersonation start event', 0),
  ('admin.user_impersonation_ended', 'admin', 'phase_admin', 'server', 'A2/B5/C2/c2-high-risk-admin-alert', 1, '100%', 28, 'ACTIVE', 'migration:c2', 'C2 authoritative read-only impersonation end event', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=1, sampling_policy='100%',
  current_revision=VALUES(current_revision), status='ACTIVE', is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1
 WHERE s.event_name IN (
   'admin.user_frozen', 'admin.user_unfrozen',
   'admin.user_impersonation_started', 'admin.user_impersonation_ended');

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'admin.user_frozen' event_name, 'target_user_id' property_name, 'id' property_type UNION ALL
    SELECT 'admin.user_frozen', 'operator', 'id' UNION ALL
    SELECT 'admin.user_frozen', 'reason', 'string' UNION ALL
    SELECT 'admin.user_frozen', 'occurred_at', 'timestamp' UNION ALL
    SELECT 'admin.user_unfrozen', 'target_user_id', 'id' UNION ALL
    SELECT 'admin.user_unfrozen', 'operator', 'id' UNION ALL
    SELECT 'admin.user_unfrozen', 'reason', 'string' UNION ALL
    SELECT 'admin.user_unfrozen', 'occurred_at', 'timestamp' UNION ALL
    SELECT 'admin.user_impersonation_started', 'target_user_id', 'id' UNION ALL
    SELECT 'admin.user_impersonation_started', 'operator', 'id' UNION ALL
    SELECT 'admin.user_impersonation_started', 'reason', 'string' UNION ALL
    SELECT 'admin.user_impersonation_started', 'ttl_minutes', 'number' UNION ALL
    SELECT 'admin.user_impersonation_started', 'session_start', 'timestamp' UNION ALL
    SELECT 'admin.user_impersonation_started', 'occurred_at', 'timestamp' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'target_user_id', 'id' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'operator', 'id' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'reason', 'string' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'ttl_minutes', 'number' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'session_start', 'timestamp' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'session_end', 'timestamp' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'duration_sec', 'number' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'end_type', 'enum' UNION ALL
    SELECT 'admin.user_impersonation_ended', 'occurred_at', 'timestamp'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision)
VALUES (1, 28)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision, VALUES(current_revision));
