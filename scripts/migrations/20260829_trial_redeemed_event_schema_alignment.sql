-- Align trial.redeemed's governed A4 schema with both canonical H2 conversion paths.
-- The lifecycle payload always carries the same settlement + order identity fields.
SET NAMES utf8mb4;

UPDATE nx_event_schema_registry
   SET current_revision=311,
       producer='AppTrialLifecycleService',
       consumers='A4/H2/E4/D4',
       is_server_authoritative=1,
       sampling_policy='100%',
       status='ACTIVE',
       updated_by='migration:trial-redeemed-schema-alignment',
       reason='H2 trial redemption settlement and order payload alignment',
       is_deleted=0
 WHERE event_name='trial.redeemed';

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,311,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'trial.redeemed' event_name,'order_no' property_name,'id' property_type UNION ALL
    SELECT 'trial.redeemed','product_no','id' UNION ALL
    SELECT 'trial.redeemed','discount_usdt','number' UNION ALL
    SELECT 'trial.redeemed','payment_status','enum' UNION ALL
    SELECT 'trial.redeemed','order_status','enum'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.registry_revision=311,p.is_deleted=0
 WHERE s.event_name='trial.redeemed';

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,311)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,311);
