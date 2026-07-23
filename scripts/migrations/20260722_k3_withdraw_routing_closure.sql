-- K3/A4 closure: register the server-canonical withdrawal hold event consumed by D2/B5/K5.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('risk.withdraw_held', 'risk', 'risk', 'AppWithdrawalService', 'A4/D2/B5/K3/K5', 1,
   '100%', 167, 'ACTIVE', 'migration:k3-a4',
   'K3 server-canonical withdrawal route held before D2 approval', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=1, sampling_policy='100%',
  current_revision=VALUES(current_revision), status='ACTIVE', updated_by='migration:k3-a4',
  reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name='risk.withdraw_held'
   AND p.property_name NOT IN ('rule_id','action','withdrawal_id','amount_usdt','dimension');

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'risk.withdraw_held' event_name, 'rule_id' property_name, 'id' property_type UNION ALL
    SELECT 'risk.withdraw_held', 'action', 'enum' UNION ALL
    SELECT 'risk.withdraw_held', 'withdrawal_id', 'id' UNION ALL
    SELECT 'risk.withdraw_held', 'amount_usdt', 'number' UNION ALL
    SELECT 'risk.withdraw_held', 'dimension', 'enum'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

-- K3 and K5 enrich the canonical D2 submission fact. Register the enrichment
-- before production withdrawal submission publishes it; nullable identifiers
-- remain optional when the corresponding route/review did not trigger.
UPDATE nx_event_schema_registry
   SET current_revision=GREATEST(current_revision,167), updated_by='migration:k3-a4'
 WHERE event_name='withdraw.submitted' AND is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, p.required_field, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'withdraw.submitted' event_name, 'risk_route' property_name, 'enum' property_type, 1 required_field UNION ALL
    SELECT 'withdraw.submitted', 'risk_rule_id', 'id', 0 UNION ALL
    SELECT 'withdraw.submitted', 'k5_ticket_id', 'id', 0
  ) p ON p.event_name=s.event_name
 WHERE s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
VALUES
  ('risk', 'risk.withdraw_held', 'AppWithdrawalService', 'A4/D2/B5/K3/K5',
   'DONE', 'migration:k3-a4', 'K3 withdrawal routing hold consumed by D2/B5/K5', 0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer), consumer=VALUES(consumer), status='DONE',
  reason=VALUES(reason), is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision) VALUES (1,167)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,167);

-- K3 permission closure follows the current A1 built-in role set. Finance and
-- auditors are read-only; the current RISK role owns K3 operations. Do not
-- revive the removed lead/member role split through a legacy role code.
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code='risk_k3_read'
 WHERE r.role_code IN ('FINANCE','AUDITOR')
   AND r.status=1 AND r.is_deleted=0
   AND p.status=1 AND p.is_deleted=0;

-- Read permission must be discoverable from the visible sidebar. Grant the K3
-- leaf menu to exactly the same current built-in roles; the shell derives the
-- K-domain group from its visible children.
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id,m.id
  FROM nx_admin_role r
  JOIN nx_admin_menu m ON m.menu_code='K3'
 WHERE r.role_code IN ('FINANCE','AUDITOR','RISK','SUPER_ADMIN')
   AND r.status=1 AND r.is_deleted=0
   AND m.status=1 AND m.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id
  FROM nx_admin_role r
  JOIN nx_admin_permission p
    ON p.permission_code IN ('risk_k3_read','risk_k3_write','risk_k3_rule_create','risk_k3_rule_toggle','risk_k3_rule_archive')
 WHERE r.role_code='RISK'
   AND r.status=1 AND r.is_deleted=0
   AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code LIKE 'risk_k3_%'
 WHERE r.role_code='SUPER_ADMIN'
   AND r.status=1 AND r.is_deleted=0
   AND p.status=1 AND p.is_deleted=0;
