-- Restores the complete commission.paid property set at one active revision.
-- Revision 285 added network attribution but did not carry the pre-existing
-- required money properties forward. EventOutboxService therefore rejected
-- real F2/F3/F4/F5 producers and rolled their surrounding transactions back.
SET NAMES utf8mb4;

UPDATE nx_event_schema_registry
   SET producer='F2/F3/F4/F5 settlement',
       consumers='A4/D4/B1/B2/F4/F5/L1/L4',
       current_revision=312,
       status='ACTIVE',
       updated_by='migration:commission-paid-revision-closure',
       reason='Complete money and network attribution contract at one active revision',
       is_deleted=0,
       updated_at=NOW()
 WHERE event_name='commission.paid';

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,312,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'kind' property_name,'enum' property_type,1 required_field
    UNION ALL SELECT 'currency','enum',1
    UNION ALL SELECT 'amount','number',1
    UNION ALL SELECT 'smaller_track_gv','number',0
    UNION ALL SELECT 'match_rate','number',0
    UNION ALL SELECT 'daily_cap_applied','boolean',0
    UNION ALL SELECT 'commission_event_id','id',0
    UNION ALL SELECT 'source_user_id','id',0
    UNION ALL SELECT 'layer','number',0
    UNION ALL SELECT 'order_no','id',0
  ) p
 WHERE s.event_name='commission.paid'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),
  pii=0,
  required_field=VALUES(required_field),
  registry_revision=312,
  is_deleted=0,
  updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision)
VALUES (1,312)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,312);
