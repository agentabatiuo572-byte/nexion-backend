-- F-002: register the canonical F5 anomaly-config governance event before
-- F5CommissionService can commit its configuration and outbox transaction.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('admin.commission_anomaly_config_changed','admin','phase_admin','F5CommissionService',
   'A2/A4/B5/F5',1,'100%',303,'ACTIVE',
   'migration:f5-commission-anomaly-event-schema',
   'migration:f5-commission-anomaly-event-schema',
   'Server-authoritative F5 commission anomaly threshold change',0)
ON DUPLICATE KEY UPDATE
  owner_domain=IF(current_revision<=303,VALUES(owner_domain),owner_domain),
  family_key=IF(current_revision<=303,VALUES(family_key),family_key),
  producer=IF(current_revision<=303,VALUES(producer),producer),
  consumers=IF(current_revision<=303,VALUES(consumers),consumers),
  is_server_authoritative=IF(current_revision<=303,VALUES(is_server_authoritative),is_server_authoritative),
  sampling_policy=IF(current_revision<=303,VALUES(sampling_policy),sampling_policy),
  status=IF(current_revision<=303,VALUES(status),status),
  updated_by=IF(current_revision<=303,VALUES(updated_by),updated_by),
  reason=IF(current_revision<=303,VALUES(reason),reason),
  is_deleted=IF(current_revision<=303,VALUES(is_deleted),is_deleted),
  updated_at=IF(current_revision<=303,NOW(),updated_at),
  current_revision=GREATEST(current_revision,303);

-- Retire only stale rev303 fields. A future schema revision is never rewritten
-- by this migration and remains fail-closed until same-revision properties exist.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.commission_anomaly_config_changed'
   AND s.current_revision=303
   AND p.property_name NOT IN (
     'before_commission_anomaly_sigma',
     'after_commission_anomaly_sigma',
     'before_layer_ratio_anomaly_pct',
     'after_layer_ratio_anomaly_pct',
     'operator',
     'reason'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,303,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'before_commission_anomaly_sigma' property_name,'number' property_type,1 required_field UNION ALL
    SELECT 'after_commission_anomaly_sigma','number',1 UNION ALL
    SELECT 'before_layer_ratio_anomaly_pct','number',1 UNION ALL
    SELECT 'after_layer_ratio_anomaly_pct','number',1 UNION ALL
    SELECT 'operator','string',1 UNION ALL
    SELECT 'reason','string',1
  ) p
 WHERE s.event_name='admin.commission_anomaly_config_changed'
   AND s.current_revision=303
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=303,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,303)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,303);
