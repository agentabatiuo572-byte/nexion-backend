-- Align the timed D2 lifecycle event with the payload emitted by the due-hold scheduler.
-- A review can become due precisely because current K4 facts are unavailable, so
-- risk_score is optional and risk_score_status carries the fail-closed reason.

UPDATE nx_event_schema_registry
   SET current_revision=310,
       status='ACTIVE',
       is_deleted=0,
       updated_at=NOW()
 WHERE event_name='withdraw.review_due';

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry r ON r.id=p.schema_id
   SET p.required_field=0,
       p.registry_revision=310,
       p.is_deleted=0,
       p.updated_at=NOW()
 WHERE r.event_name='withdraw.review_due'
   AND p.property_name='risk_score';

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision,
   created_at, updated_at, is_deleted)
SELECT r.id,'risk_score_status','string',0,0,310,NOW(),NOW(),0
  FROM nx_event_schema_registry r
 WHERE r.event_name='withdraw.review_due'
ON DUPLICATE KEY UPDATE
  property_type='string',
  pii=0,
  required_field=0,
  registry_revision=310,
  is_deleted=0,
  updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry r ON r.id=p.schema_id
   SET p.registry_revision=310,
       p.updated_at=NOW()
 WHERE r.event_name='withdraw.review_due'
   AND p.is_deleted=0;
