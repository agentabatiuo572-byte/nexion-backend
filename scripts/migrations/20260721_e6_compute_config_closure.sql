USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- E6 exact split: Growth can propose the forward-spec flag only; coefficients
-- and computer mapping/download parameters remain SUPER_ADMIN writes.
INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('device_e6_flag_toggle','E6 电脑算力入口开关','API','/devices/compute-config','HIGH',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_path=VALUES(resource_path),
  perm_type='HIGH',amplifies=0,status=1,is_deleted=0,updated_at=NOW();

INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT role_row.id,permission_row.id
  FROM nx_admin_role role_row
  JOIN nx_admin_permission permission_row
    ON permission_row.permission_code='device_e6_flag_toggle'
 WHERE role_row.role_code IN ('GROWTH','SUPER_ADMIN')
   AND role_row.status=1 AND role_row.is_deleted=0
   AND permission_row.status=1 AND permission_row.is_deleted=0;

UPDATE nx_admin_role_permission role_permission
JOIN nx_admin_role role_row ON role_row.id=role_permission.role_id
JOIN nx_admin_permission permission_row ON permission_row.id=role_permission.permission_id
   SET role_permission.is_deleted=0
 WHERE role_row.role_code IN ('GROWTH','SUPER_ADMIN')
   AND permission_row.permission_code='device_e6_flag_toggle';

UPDATE nx_admin_role_permission role_permission
JOIN nx_admin_role role_row ON role_row.id=role_permission.role_id
JOIN nx_admin_permission permission_row ON permission_row.id=role_permission.permission_id
   SET role_permission.is_deleted=1
 WHERE role_row.role_code='GROWTH'
   AND permission_row.permission_code='device_e6_write';

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('compute.flag_toggled','compute','compute-config','server','E6/A2/A4/App',1,'100%',58,
   'ACTIVE','migration:e6-compute-config','E6 computer compute forward-spec flag',0),
  ('compute.coefficient_changed','compute','compute-config','server','E6/A2/A4/App',1,'100%',59,
   'ACTIVE','migration:e6-compute-config','E6 online bonus coefficient',0),
  ('compute.config_changed','compute','compute-config','server','E6/A2/A4/App',1,'100%',60,
   'ACTIVE','migration:e6-compute-config','E6 atomic multi-parameter configuration',0),
  ('compute.param_changed','compute','compute-config','server','E6/A2/A4/App',1,'100%',61,
   'ACTIVE','migration:e6-compute-config','E6 single GPU yield or download parameter',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),
  producer='server',consumers=VALUES(consumers),is_server_authoritative=1,
  sampling_policy='100%',current_revision=VALUES(current_revision),status='ACTIVE',
  updated_by='migration:e6-compute-config',reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT schema_row.id,property_row.property_name,property_row.property_type,0,1,schema_row.current_revision,0
  FROM nx_event_schema_registry schema_row
  JOIN (
    SELECT 'compute.flag_toggled' event_name,'param_key' property_name,'string' property_type UNION ALL
    SELECT 'compute.flag_toggled','before','string' UNION ALL
    SELECT 'compute.flag_toggled','after','string' UNION ALL
    SELECT 'compute.flag_toggled','operator','id' UNION ALL
    SELECT 'compute.flag_toggled','reason','string' UNION ALL
    SELECT 'compute.flag_toggled','ts','timestamp' UNION ALL
    SELECT 'compute.coefficient_changed','param_key','string' UNION ALL
    SELECT 'compute.coefficient_changed','before','string' UNION ALL
    SELECT 'compute.coefficient_changed','after','string' UNION ALL
    SELECT 'compute.coefficient_changed','operator','id' UNION ALL
    SELECT 'compute.coefficient_changed','reason','string' UNION ALL
    SELECT 'compute.coefficient_changed','ts','timestamp' UNION ALL
    SELECT 'compute.param_changed','param_key','string' UNION ALL
    SELECT 'compute.param_changed','before','string' UNION ALL
    SELECT 'compute.param_changed','after','string' UNION ALL
    SELECT 'compute.param_changed','operator','id' UNION ALL
    SELECT 'compute.param_changed','reason','string' UNION ALL
    SELECT 'compute.param_changed','ts','timestamp' UNION ALL
    SELECT 'compute.config_changed','param_key','string' UNION ALL
    SELECT 'compute.config_changed','keys','json' UNION ALL
    SELECT 'compute.config_changed','before','json' UNION ALL
    SELECT 'compute.config_changed','after','json' UNION ALL
    SELECT 'compute.config_changed','operator','id' UNION ALL
    SELECT 'compute.config_changed','reason','string' UNION ALL
    SELECT 'compute.config_changed','ts','timestamp'
  ) property_row ON property_row.event_name=schema_row.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

-- The generic event supports single and batch payloads; make the mutually
-- exclusive param_key/keys optional while keeping before/after mandatory.
UPDATE nx_event_schema_property property_row
JOIN nx_event_schema_registry schema_row ON schema_row.id=property_row.schema_id
   SET property_row.required_field=0
 WHERE schema_row.event_name='compute.config_changed'
   AND property_row.property_name IN ('param_key','keys');

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('compute','compute.flag_toggled','OpsDeviceService','E6/A2/A4/App','REGISTERED',
   'migration:e6-compute-config','E6 flag event contract',0),
  ('compute','compute.coefficient_changed','OpsDeviceService','E6/A2/A4/App','REGISTERED',
   'migration:e6-compute-config','E6 coefficient event contract',0),
  ('compute','compute.config_changed','OpsDeviceService','E6/A2/A4/App','REGISTERED',
   'migration:e6-compute-config','E6 atomic config event contract',0),
  ('compute','compute.param_changed','OpsDeviceService','E6/A2/A4/App','REGISTERED',
   'migration:e6-compute-config','E6 single config event contract',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,61)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,61);
