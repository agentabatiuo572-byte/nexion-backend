-- F19: the F5 CSV export is a high-sensitivity A2/A4 action.  The producer
-- already emits admin.commission_exported, so register its exact redacted
-- payload before allowing the export transaction to commit.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('admin.commission_exported','admin','phase_admin','F5CommissionService',
   'A2/A4/F5',1,'100%',304,'ACTIVE',
   'migration:f5-commission-export-event-schema',
   'migration:f5-commission-export-event-schema',
   'Server-authoritative, redacted F5 commission CSV export',0)
ON DUPLICATE KEY UPDATE
  owner_domain=IF(current_revision<=304,VALUES(owner_domain),owner_domain),
  family_key=IF(current_revision<=304,VALUES(family_key),family_key),
  producer=IF(current_revision<=304,VALUES(producer),producer),
  consumers=IF(current_revision<=304,VALUES(consumers),consumers),
  is_server_authoritative=IF(current_revision<=304,VALUES(is_server_authoritative),is_server_authoritative),
  sampling_policy=IF(current_revision<=304,VALUES(sampling_policy),sampling_policy),
  status=IF(current_revision<=304,VALUES(status),status),
  updated_by=IF(current_revision<=304,VALUES(updated_by),updated_by),
  reason=IF(current_revision<=304,VALUES(reason),reason),
  is_deleted=IF(current_revision<=304,VALUES(is_deleted),is_deleted),
  updated_at=IF(current_revision<=304,NOW(),updated_at),
  current_revision=GREATEST(current_revision,304);

-- A later schema revision is preserved: only align this migration's own
-- revision, and let the outbox gate fail closed for any future contract drift.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.commission_exported'
   AND s.current_revision=304
   AND p.property_name NOT IN (
     'reason',
     'filters',
     'row_count',
     'byte_size',
     'sha256',
     'redacted'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,304,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'reason' property_name,'string' property_type,1 required_field UNION ALL
    SELECT 'filters','json',1 UNION ALL
    SELECT 'row_count','number',1 UNION ALL
    SELECT 'byte_size','number',1 UNION ALL
    SELECT 'sha256','string',1 UNION ALL
    SELECT 'redacted','boolean',1
  ) p
 WHERE s.event_name='admin.commission_exported'
   AND s.current_revision=304
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=304,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,304)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,304);
