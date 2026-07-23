-- I6/I7 A4 closure: the H3 consumer keeps its durable delivery type
-- LEARNING_COURSE_COMPLETED, while A4 governs the same fact under the canonical
-- learn.course_completed event name. Raw PII is not part of this schema.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('learn.course_completed', 'learn', 'learn', 'AppLearningService', 'A4/I7/H3/B1/B3/D/L', 1,
   '100%', 116, 'ACTIVE', 'migration:i6-i7-a4', 'I7 server-validated quiz completion and NEX reward fact', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy='100%', current_revision=VALUES(current_revision), status='ACTIVE',
  updated_by='migration:i6-i7-a4', reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name='learn.course_completed'
   AND p.property_name NOT IN ('course_id','course_version','nex_reward');

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'learn.course_completed' event_name, 'course_id' property_name, 'id' property_type UNION ALL
    SELECT 'learn.course_completed', 'course_version', 'id' UNION ALL
    SELECT 'learn.course_completed', 'nex_reward', 'number'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
VALUES
  ('learn', 'learn.course_completed', 'AppLearningService', 'A4/I7/H3/B1/B3/D/L',
   'DONE', 'migration:i6-i7-a4', 'I7 server-authoritative course completion and NEX reward', 0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer), consumer=VALUES(consumer), status='DONE',
  reason=VALUES(reason), is_deleted=0;

-- Repair already persisted delivery facts so BI/A4 never sees a split historical
-- series. New rows are governed before insert by EventOutboxService.
UPDATE nx_event_outbox o
JOIN nx_event_schema_registry s ON s.event_name='learn.course_completed'
   SET o.event_name=s.event_name,
       o.family_key=s.family_key,
       o.is_server_authoritative=s.is_server_authoritative,
       o.schema_revision=s.current_revision,
       o.schema_registered=1,
       o.analytics_event=1,
       o.payload=JSON_SET(o.payload,
         '$.event_name', s.event_name,
         '$.is_server_authoritative', CAST(s.is_server_authoritative AS UNSIGNED),
         '$.schema_revision', s.current_revision,
         '$.nex_reward', COALESCE(
           JSON_EXTRACT(o.payload,'$.nex_reward'),
           (SELECT r.amount_nex
              FROM nx_learning_reward_ledger r
             WHERE r.reward_no=CONCAT('LEARN:', JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.user_id')), ':',
                                      JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.course_id')), ':',
                                      JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.course_version')))
               AND r.is_deleted=0
             LIMIT 1),
           0))
 WHERE o.event_type='LEARNING_COURSE_COMPLETED'
   AND o.is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision) VALUES (1,116)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,116);
