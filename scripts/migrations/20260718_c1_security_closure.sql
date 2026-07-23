-- C1 security and role-matrix closure.
-- Depends on 20260712_rbac_classic.sql and 20260717_a4_event_governance_closure.sql.

-- FINANCE: C1 list + role-trimmed 360 (no export).
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='FINANCE'
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=0
WHERE p.permission_code IN ('user_c1_read','user_c1hub_read');
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE'
  AND p.permission_code IN ('user_c1_read','user_c1hub_read')
  AND p.status=1 AND p.is_deleted=0;

-- GROWTH: C1 list + role-trimmed 360 + masked export.
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='GROWTH'
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=0
WHERE p.permission_code IN ('user_c1_read','user_c1_write','user_c1hub_read');
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='GROWTH'
  AND p.permission_code IN ('user_c1_read','user_c1_write','user_c1hub_read')
  AND p.status=1 AND p.is_deleted=0;

-- AUDITOR export is the PRD-approved masked evidence export, not a business mutation.
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='AUDITOR'
JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.permission_code='user_c1_write'
SET rp.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='AUDITOR' AND p.permission_code='user_c1_write'
  AND p.status=1 AND p.is_deleted=0;

-- FINANCE/GROWTH receive only the C parent and C1 menu, not C2-C6 mutation surfaces.
UPDATE nx_admin_role_menu rm
JOIN nx_admin_role r ON r.id=rm.role_id AND r.role_code IN ('FINANCE','GROWTH')
JOIN nx_admin_menu m ON m.id=rm.menu_id AND m.menu_code IN ('C','C1')
SET rm.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m
WHERE r.role_code IN ('FINANCE','GROWTH') AND m.menu_code IN ('C','C1')
  AND m.status=1 AND m.is_deleted=0;

-- Register the two server-authoritative C1 admin events before publishers are enabled.
INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('admin.user_profile_viewed', 'admin', 'phase_admin', 'server', 'A2/L5', 1, '100%', 23, 'ACTIVE',
   'migration:c1', 'Register C1 sensitive profile-view evidence', 0),
  ('admin.user_list_exported', 'admin', 'phase_admin', 'server', 'A2/L5', 1, '100%', 24, 'ACTIVE',
   'migration:c1', 'Register C1 masked list-export evidence', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy=VALUES(sampling_policy), current_revision=VALUES(current_revision),
  status='ACTIVE', updated_by='migration:c1', reason=VALUES(reason), is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'admin.user_profile_viewed' event_name, 'target_user_id' property_name, 'id' property_type UNION ALL
  SELECT 'admin.user_profile_viewed', 'viewer_operator', 'id' UNION ALL
  SELECT 'admin.user_profile_viewed', 'viewer_role', 'enum' UNION ALL
  SELECT 'admin.user_profile_viewed', 'cards_viewed', 'json' UNION ALL
  SELECT 'admin.user_profile_viewed', 'occurred_at', 'timestamp' UNION ALL
  SELECT 'admin.user_list_exported', 'filter_hash', 'string' UNION ALL
  SELECT 'admin.user_list_exported', 'row_count', 'number' UNION ALL
  SELECT 'admin.user_list_exported', 'exporter_operator', 'id' UNION ALL
  SELECT 'admin.user_list_exported', 'exporter_role', 'enum' UNION ALL
  SELECT 'admin.user_list_exported', 'occurred_at', 'timestamp'
) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision)
VALUES (1, 24)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision, VALUES(current_revision));
