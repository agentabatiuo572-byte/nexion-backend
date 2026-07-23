-- I4/A4 closure: register trust-center exposure and governance events before
-- their producers write to the governed transactional outbox.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('content.trust_section_viewed', 'content', 'conversion', 'NexionUniApp:/pages/trust/trust', 'A4/I4/B3/L', 0,
   '100%', 160, 'ACTIVE', 'migration:i4-a4', 'I4 client-rendered trust section exposure', 0),
  ('admin.trust_content_published', 'admin', 'phase_admin', 'OpsTrustDisclosureService', 'A4/I4/A2', 1,
   '100%', 161, 'ACTIVE', 'migration:i4-a4', 'I4 published trust content governance event', 0),
  ('admin.trust_content_archived', 'admin', 'phase_admin', 'OpsTrustDisclosureService', 'A4/I4/A2', 1,
   '100%', 162, 'ACTIVE', 'migration:i4-a4', 'I4 archived trust content governance event', 0),
  ('admin.trust_content_rolledback', 'admin', 'phase_admin', 'OpsTrustDisclosureService', 'A4/I4/A2', 1,
   '100%', 163, 'ACTIVE', 'migration:i4-a4', 'I4 rolled back trust content governance event', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy='100%', current_revision=VALUES(current_revision), status='ACTIVE',
  updated_by='migration:i4-a4', reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name IN (
       'content.trust_section_viewed', 'admin.trust_content_published',
       'admin.trust_content_archived', 'admin.trust_content_rolledback')
   AND ((s.event_name='content.trust_section_viewed'
         AND p.property_name NOT IN ('section_key'))
        OR (s.event_name='admin.trust_content_published'
            AND p.property_name NOT IN ('section_key','from_version','to_version','operator','operator_role','reason','data_source_statement'))
        OR (s.event_name='admin.trust_content_archived'
            AND p.property_name NOT IN ('section_key','version','operator','operator_role','reason'))
        OR (s.event_name='admin.trust_content_rolledback'
            AND p.property_name NOT IN ('section_key','from_version','to_version','operator','operator_role','reason')));

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, p.required_field, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'content.trust_section_viewed' event_name, 'section_key' property_name, 'enum' property_type, 1 required_field UNION ALL
    SELECT 'admin.trust_content_published', 'section_key', 'enum', 1 UNION ALL
    SELECT 'admin.trust_content_published', 'from_version', 'id', 1 UNION ALL
    SELECT 'admin.trust_content_published', 'to_version', 'id', 1 UNION ALL
    SELECT 'admin.trust_content_published', 'operator', 'id', 1 UNION ALL
    SELECT 'admin.trust_content_published', 'operator_role', 'enum', 1 UNION ALL
    SELECT 'admin.trust_content_published', 'reason', 'string', 1 UNION ALL
    SELECT 'admin.trust_content_published', 'data_source_statement', 'string', 0 UNION ALL
    SELECT 'admin.trust_content_archived', 'section_key', 'enum', 1 UNION ALL
    SELECT 'admin.trust_content_archived', 'version', 'id', 1 UNION ALL
    SELECT 'admin.trust_content_archived', 'operator', 'id', 1 UNION ALL
    SELECT 'admin.trust_content_archived', 'operator_role', 'enum', 1 UNION ALL
    SELECT 'admin.trust_content_archived', 'reason', 'string', 1 UNION ALL
    SELECT 'admin.trust_content_rolledback', 'section_key', 'enum', 1 UNION ALL
    SELECT 'admin.trust_content_rolledback', 'from_version', 'id', 1 UNION ALL
    SELECT 'admin.trust_content_rolledback', 'to_version', 'id', 1 UNION ALL
    SELECT 'admin.trust_content_rolledback', 'operator', 'id', 1 UNION ALL
    SELECT 'admin.trust_content_rolledback', 'operator_role', 'enum', 1 UNION ALL
    SELECT 'admin.trust_content_rolledback', 'reason', 'string', 1
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
VALUES
  ('content', 'content.trust_section_viewed', 'NexionUniApp:/pages/trust/trust', 'A4/I4/B3/L',
   'DONE', 'migration:i4-a4', 'I4 trust section exposure', 0),
  ('admin', 'admin.trust_content_published', 'OpsTrustDisclosureService', 'A4/I4/A2',
   'DONE', 'migration:i4-a4', 'I4 trust content published', 0),
  ('admin', 'admin.trust_content_archived', 'OpsTrustDisclosureService', 'A4/I4/A2',
   'DONE', 'migration:i4-a4', 'I4 trust content archived', 0),
  ('admin', 'admin.trust_content_rolledback', 'OpsTrustDisclosureService', 'A4/I4/A2',
   'DONE', 'migration:i4-a4', 'I4 trust content rolled back', 0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer), consumer=VALUES(consumer), status='DONE',
  reason=VALUES(reason), is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision) VALUES (1,163)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,163);
