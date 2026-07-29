-- D-001: D2 may legally delay a withdrawal while K4 risk scoring is
-- unavailable. Keep all other withdrawal event schemas unchanged.
UPDATE nx_event_schema_registry
   SET current_revision=15,
       updated_at=NOW()
 WHERE event_name='withdraw.delayed'
   AND status='ACTIVE'
   AND is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field,
   registry_revision, created_at, updated_at, is_deleted)
SELECT s.id,'risk_score_status','string',0,0,15,NOW(),NOW(),0
  FROM nx_event_schema_registry s
 WHERE s.event_name='withdraw.delayed'
   AND s.status='ACTIVE'
   AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),
  required_field=0,
  registry_revision=15,
  is_deleted=0,
  updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.required_field=0,
       p.registry_revision=15,
       p.is_deleted=0,
       p.updated_at=NOW()
 WHERE s.event_name='withdraw.delayed'
   AND s.status='ACTIVE'
   AND s.is_deleted=0
   AND p.property_name='risk_score';

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.registry_revision=15,
       p.updated_at=NOW()
 WHERE s.event_name='withdraw.delayed'
   AND s.status='ACTIVE'
   AND s.is_deleted=0
   AND p.is_deleted=0;
