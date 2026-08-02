-- L-A4-SCHEMA-007: extend admin.report_exported for immutable artifact evidence.
-- The three artifact fields remain optional because D4, L6 and regulatory
-- producers still publish the legacy report-export payload.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('admin.report_exported','admin','phase_admin','L5/D4 report services',
   'A2/A4/L5/J4',1,'100%',302,'ACTIVE',
   'migration:l2-report-export-artifact-schema',
   'migration:l2-report-export-artifact-schema',
   'Server-authoritative report export with optional immutable artifact evidence',0)
ON DUPLICATE KEY UPDATE
  owner_domain=IF(current_revision<=302,VALUES(owner_domain),owner_domain),
  family_key=IF(current_revision<=302,VALUES(family_key),family_key),
  producer=IF(current_revision<=302,VALUES(producer),producer),
  consumers=IF(current_revision<=302,VALUES(consumers),consumers),
  is_server_authoritative=IF(current_revision<=302,VALUES(is_server_authoritative),is_server_authoritative),
  sampling_policy=IF(current_revision<=302,VALUES(sampling_policy),sampling_policy),
  status=IF(current_revision<=302,VALUES(status),status),
  updated_by=IF(current_revision<=302,VALUES(updated_by),updated_by),
  reason=IF(current_revision<=302,VALUES(reason),reason),
  is_deleted=IF(current_revision<=302,VALUES(is_deleted),is_deleted),
  updated_at=IF(current_revision<=302,NOW(),updated_at),
  current_revision=GREATEST(current_revision,302);

-- Preserve the full rev289 contract and activate the three rev302 additions.
-- Any property outside this exact producer contract remains fail-closed.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.report_exported'
   AND s.current_revision=302
   AND p.property_name NOT IN (
     'report_id','export_type','scope','row_count','contains_pii',
     'masking_policy','operator','reason','format',
     'template_code','jurisdiction_code','disclosure_version',
     'artifact_store','artifact_sha256','artifact_size_bytes'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,302,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'report_id' property_name,'id' property_type,1 required_field UNION ALL
    SELECT 'export_type','enum',1 UNION ALL
    SELECT 'scope','string',1 UNION ALL
    SELECT 'row_count','number',1 UNION ALL
    SELECT 'contains_pii','boolean',1 UNION ALL
    SELECT 'masking_policy','enum',1 UNION ALL
    SELECT 'operator','string',1 UNION ALL
    SELECT 'reason','string',1 UNION ALL
    SELECT 'format','enum',1 UNION ALL
    SELECT 'template_code','enum',0 UNION ALL
    SELECT 'jurisdiction_code','string',0 UNION ALL
    SELECT 'disclosure_version','string',0 UNION ALL
    SELECT 'artifact_store','string',0 UNION ALL
    SELECT 'artifact_sha256','string',0 UNION ALL
    SELECT 'artifact_size_bytes','number',0
  ) p
 WHERE s.event_name='admin.report_exported'
   AND s.current_revision=302
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=302,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,302)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,302);
