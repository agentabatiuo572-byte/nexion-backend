-- I5/A4 closure: register the disclosure user, re-ack, and gate events before
-- the transactional producers emit them. No raw PII is admitted to the schema.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('disclosure.viewed', 'disclosure', 'compliance', 'AppRiskDisclosureService', 'A4/I5/B3/L/K', 1,
   '100%', 112, 'ACTIVE', 'migration:i5-a4', 'I5 server-confirmed disclosure exposure', 0),
  ('disclosure.acked', 'disclosure', 'compliance', 'AppRiskDisclosureService', 'A4/I5/L/K', 1,
   '100%', 113, 'ACTIVE', 'migration:i5-a4', 'I5 one-time-token acknowledgment committed', 0),
  ('disclosure.reack_triggered', 'disclosure', 'compliance', 'I5ReackNotifier', 'A4/I3/I5/L/K', 1,
   '100%', 114, 'ACTIVE', 'migration:i5-a4', 'I5 mapping activation makes affected users stale', 0),
  ('disclosure.gated_action_blocked', 'disclosure', 'compliance', 'AppRiskDisclosureService', 'A4/I5/K/L', 1,
   '100%', 115, 'ACTIVE', 'migration:i5-a4', 'I5 server gate blocks a stale or missing acknowledgment', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy='100%', current_revision=VALUES(current_revision), status='ACTIVE',
  updated_by='migration:i5-a4', reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name IN (
       'disclosure.viewed', 'disclosure.acked',
       'disclosure.reack_triggered', 'disclosure.gated_action_blocked')
   AND ((s.event_name='disclosure.viewed'
         AND p.property_name NOT IN ('jurisdiction','version','gated_action_context'))
        OR (s.event_name='disclosure.acked'
            AND p.property_name NOT IN ('jurisdiction','version','dual_gate_satisfied'))
        OR (s.event_name='disclosure.reack_triggered'
            AND p.property_name NOT IN ('jurisdiction','from_version','to_version','affected_user_count'))
        OR (s.event_name='disclosure.gated_action_blocked'
            AND p.property_name NOT IN ('jurisdiction','version','gated_action','business_flow_id')));

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'disclosure.viewed' event_name, 'jurisdiction' property_name, 'enum' property_type UNION ALL
    SELECT 'disclosure.viewed', 'version', 'id' UNION ALL
    SELECT 'disclosure.viewed', 'gated_action_context', 'enum' UNION ALL
    SELECT 'disclosure.acked', 'jurisdiction', 'enum' UNION ALL
    SELECT 'disclosure.acked', 'version', 'id' UNION ALL
    SELECT 'disclosure.acked', 'dual_gate_satisfied', 'boolean' UNION ALL
    SELECT 'disclosure.reack_triggered', 'jurisdiction', 'enum' UNION ALL
    SELECT 'disclosure.reack_triggered', 'from_version', 'id' UNION ALL
    SELECT 'disclosure.reack_triggered', 'to_version', 'id' UNION ALL
    SELECT 'disclosure.reack_triggered', 'affected_user_count', 'number' UNION ALL
    SELECT 'disclosure.gated_action_blocked', 'jurisdiction', 'enum' UNION ALL
    SELECT 'disclosure.gated_action_blocked', 'version', 'id' UNION ALL
    SELECT 'disclosure.gated_action_blocked', 'gated_action', 'enum' UNION ALL
    SELECT 'disclosure.gated_action_blocked', 'business_flow_id', 'id'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
VALUES
  ('disclosure', 'disclosure.viewed', 'AppRiskDisclosureService', 'A4/I5/B3/L/K',
   'DONE', 'migration:i5-a4', 'I5 disclosure exposure', 0),
  ('disclosure', 'disclosure.acked', 'AppRiskDisclosureService', 'A4/I5/L/K',
   'DONE', 'migration:i5-a4', 'I5 disclosure acknowledgment', 0),
  ('disclosure', 'disclosure.reack_triggered', 'I5ReackNotifier', 'A4/I3/I5/L/K',
   'DONE', 'migration:i5-a4', 'I5 per-user re-ack trigger', 0),
  ('disclosure', 'disclosure.gated_action_blocked', 'AppRiskDisclosureService', 'A4/I5/K/L',
   'DONE', 'migration:i5-a4', 'I5 server gate block', 0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer), consumer=VALUES(consumer), status='DONE',
  reason=VALUES(reason), is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision) VALUES (1,115)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,115);
