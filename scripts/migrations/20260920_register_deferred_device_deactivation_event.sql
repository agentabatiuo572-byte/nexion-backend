-- Register the deferred device-deactivation event so its publish can succeed.
--
-- Why: OpsDeviceService publishes 'admin.device_deactivation_requested' when a
-- deactivation is deferred until task settlement (recordE5DeviceEvent with
-- mode='deferred_until_task_settlement'), but the type was never registered in
-- nx_event_schema_registry. EventOutboxService.publish classifies a lowercase
-- dotted name as an analytics event and throws A4_SCHEMA_NOT_REGISTERED when no
-- active schema exists, so every deferred deactivation failed outright while the
-- immediate 'admin.device_deactivated' path worked. The OutboxDispatchCoverageTest
-- guard surfaced it once the producer scan could see the composed name.
--
-- The payload is byte-identical to the already-registered sibling
-- 'admin.device_deactivated' (same recordE5DeviceEvent detail map), so the schema
-- properties are reused verbatim. Registration is the correct fix rather than
-- removing the publish: the deferred request is a real operator action that A2
-- audit and the L3/L4 read models expect to see, exactly like its sibling.
--
-- Non-authoritative and record-only, matching the sibling: consumers=E5/A2/A4/L3
-- with is_server_authoritative=0, so no canonical quest can be completed from it.

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('admin.device_deactivation_requested','admin','device-ops','server','E5/A2/A4/L3',0,'100%',54,
   'ACTIVE','migration:e5-deferred-device-deactivation',NULL,
   'E5 deferred device deactivation request event (settlement-gated)',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),
  producer=VALUES(producer),consumers=VALUES(consumers),
  is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy='100%',current_revision=VALUES(current_revision),status='ACTIVE',
  updated_by=VALUES(updated_by),reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'admin.device_deactivation_requested' event_name,'device_id' property_name,'id' property_type UNION ALL
    SELECT 'admin.device_deactivation_requested','user_id','id' UNION ALL
    SELECT 'admin.device_deactivation_requested','instance_no','id' UNION ALL
    SELECT 'admin.device_deactivation_requested','before_status','enum' UNION ALL
    SELECT 'admin.device_deactivation_requested','after_status','enum' UNION ALL
    SELECT 'admin.device_deactivation_requested','mode','enum' UNION ALL
    SELECT 'admin.device_deactivation_requested','operator','id' UNION ALL
    SELECT 'admin.device_deactivation_requested','reason','string' UNION ALL
    SELECT 'admin.device_deactivation_requested','ts','timestamp'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('admin','admin.device_deactivation_requested','OpsDeviceService','E5/A2/A4/L3','REGISTERED',
   'migration:e5-deferred-device-deactivation','E5 deferred deactivation contract',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  is_deleted=0;

-- Postcondition: the type must be active with a complete property set. A bare
-- SELECT would only print the verdict, and the controlled runner does not parse
-- it, so the check raises SQLSTATE 45000 instead: mysql exits non-zero and the
-- runner stops backend startup rather than leaving the flow silently broken.
SET @deferred_device_deactivation_ready = (
  (SELECT COUNT(*) FROM nx_event_schema_registry
    WHERE event_name = 'admin.device_deactivation_requested'
      AND status = 'ACTIVE' AND is_deleted = 0) = 1
  AND (SELECT COUNT(*) FROM nx_event_schema_property p
        JOIN nx_event_schema_registry s ON s.id = p.schema_id
       WHERE s.event_name = 'admin.device_deactivation_requested'
         AND p.is_deleted = 0 AND p.registry_revision = s.current_revision) = 9
);
SET @sql = IF(
  @deferred_device_deactivation_ready,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''DEFERRED_DEVICE_DEACTIVATION_REGISTRATION_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
