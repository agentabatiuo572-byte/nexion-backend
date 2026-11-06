-- The existing G2 batch event includes these fields. Register them on the active schema.
INSERT IGNORE INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,0,s.current_revision,0
  FROM nx_event_schema_registry s
 CROSS JOIN (
   SELECT 'failed_count' property_name,'number' property_type
   UNION ALL SELECT 'outcome','string'
 ) p
 WHERE s.event_name='admin.exchange_queue_batch_processed'
   AND s.status='ACTIVE' AND s.is_deleted=0;
