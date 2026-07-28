-- L5 export closure: MinIO artifacts and concurrent, actor-bound download grants.
CREATE TABLE IF NOT EXISTS nx_bi_report_artifact (
  report_id VARCHAR(64) NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  size_bytes BIGINT NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (report_id),
  UNIQUE KEY uk_bi_report_artifact_object (object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_bi_report_download_grant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  report_id VARCHAR(64) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  issued_to_admin_id BIGINT NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bi_report_download_token (token_hash),
  KEY idx_bi_report_download_lookup
    (report_id, issued_to_admin_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.report_exported','admin','phase_admin','L5/D4 report services',
   'A2/A4/L5/J4',1,'100%',289,'ACTIVE',
   'migration:l5-export-artifact',
   'Server-authoritative report snapshot created with mandatory export audit',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin',family_key='phase_admin',producer='L5/D4 report services',
  consumers='A2/A4/L5/J4',is_server_authoritative=1,sampling_policy='100%',
  current_revision=GREATEST(current_revision,289),status='ACTIVE',
  updated_by='migration:l5-export-artifact',reason=VALUES(reason),
  is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.report_exported'
   AND p.property_name NOT IN (
     'report_id','export_type','scope','row_count','contains_pii',
     'masking_policy','operator','reason','format',
     'template_code','jurisdiction_code','disclosure_version'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,289,NOW(),NOW(),0
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
    SELECT 'disclosure_version','string',0
  ) p
 WHERE s.event_name='admin.report_exported'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=289,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,289)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,289);
