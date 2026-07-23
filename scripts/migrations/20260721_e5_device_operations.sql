USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_datacenter' AND COLUMN_NAME = 'location') = 0,
  'ALTER TABLE nx_compute_datacenter ADD COLUMN location VARCHAR(128) NOT NULL DEFAULT '''' AFTER region_label', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_datacenter' AND COLUMN_NAME = 'display_name') = 0,
  'ALTER TABLE nx_compute_datacenter ADD COLUMN display_name VARCHAR(128) NOT NULL DEFAULT '''' AFTER location', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_compute_datacenter SET location=region_label WHERE location='' OR location IS NULL;
UPDATE nx_compute_datacenter SET display_name=region_label WHERE display_name='' OR display_name IS NULL;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
SET p.is_deleted=1, p.updated_at=NOW()
WHERE s.event_name IN ('admin.datacenter_created','admin.datacenter_updated','admin.datacenter_deleted')
  AND p.property_name='displayName';

-- E5 canonical operator events. Device activation is a trusted OpsDeviceService
-- state transition consumed by H3; the remaining manual operator events stay
-- non-authoritative and cannot complete canonical user quests.
-- MAX_DEVICES=6 remains a Java invariant; no editable configuration row is introduced.
INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('admin.device_activated','admin','device-ops','server','E5/A2/A4/L3/H3',1,'100%',51,
   'ACTIVE','migration:e5-device-operations','migration:h3-trusted-device-activation',
   'E5 trusted server device activation for H3 canonical quest',0),
  ('admin.device_paused','admin','device-ops','server','E5/A2/A4/L3',0,'100%',52,
   'ACTIVE','migration:e5-device-operations',NULL,'E5 user scoped device pause event',0),
  ('admin.device_resumed','admin','device-ops','server','E5/A2/A4/L3',0,'100%',53,
   'ACTIVE','migration:e5-device-operations',NULL,'E5 user scoped device resume event',0),
  ('admin.device_deactivated','admin','device-ops','server','E5/A2/A4/L3',0,'100%',54,
   'ACTIVE','migration:e5-device-operations',NULL,'E5 manual device deactivation event',0),
  ('admin.datacenter_created','admin','device-datacenter','server','E5/A2/A4',0,'100%',55,
   'ACTIVE','migration:e5-device-operations',NULL,'E5 datacenter creation event',0),
  ('admin.datacenter_updated','admin','device-datacenter','server','E5/A2/A4',0,'100%',56,
   'ACTIVE','migration:e5-device-operations',NULL,'E5 datacenter update event',0),
  ('admin.datacenter_deleted','admin','device-datacenter','server','E5/A2/A4',0,'100%',57,
   'ACTIVE','migration:e5-device-operations',NULL,'E5 datacenter deletion event',0)
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
    SELECT 'admin.device_activated' event_name,'device_id' property_name,'id' property_type UNION ALL
    SELECT 'admin.device_activated','user_id','id' UNION ALL
    SELECT 'admin.device_activated','instance_no','id' UNION ALL
    SELECT 'admin.device_activated','before_status','enum' UNION ALL
    SELECT 'admin.device_activated','after_status','enum' UNION ALL
    SELECT 'admin.device_activated','mode','enum' UNION ALL
    SELECT 'admin.device_activated','operator','id' UNION ALL
    SELECT 'admin.device_activated','reason','string' UNION ALL
    SELECT 'admin.device_activated','ts','timestamp' UNION ALL
    SELECT 'admin.device_paused','user_id','id' UNION ALL
    SELECT 'admin.device_paused','changed_count','number' UNION ALL
    SELECT 'admin.device_paused','operator','id' UNION ALL
    SELECT 'admin.device_paused','reason','string' UNION ALL
    SELECT 'admin.device_paused','ts','timestamp' UNION ALL
    SELECT 'admin.device_resumed','user_id','id' UNION ALL
    SELECT 'admin.device_resumed','changed_count','number' UNION ALL
    SELECT 'admin.device_paused','device_ids','json' UNION ALL
    SELECT 'admin.device_paused','scope','enum' UNION ALL
    SELECT 'admin.device_resumed','device_ids','json' UNION ALL
    SELECT 'admin.device_resumed','scope','enum' UNION ALL
    SELECT 'admin.device_resumed','operator','id' UNION ALL
    SELECT 'admin.device_resumed','reason','string' UNION ALL
    SELECT 'admin.device_resumed','ts','timestamp' UNION ALL
    SELECT 'admin.device_deactivated','device_id','id' UNION ALL
    SELECT 'admin.device_deactivated','user_id','id' UNION ALL
    SELECT 'admin.device_deactivated','instance_no','id' UNION ALL
    SELECT 'admin.device_deactivated','before_status','enum' UNION ALL
    SELECT 'admin.device_deactivated','after_status','enum' UNION ALL
    SELECT 'admin.device_deactivated','mode','enum' UNION ALL
    SELECT 'admin.device_deactivated','operator','id' UNION ALL
    SELECT 'admin.device_deactivated','reason','string' UNION ALL
    SELECT 'admin.device_deactivated','ts','timestamp' UNION ALL
    SELECT 'admin.datacenter_created','id','id' UNION ALL
    SELECT 'admin.datacenter_created','display_name','string' UNION ALL
    SELECT 'admin.datacenter_created','operator','id' UNION ALL
    SELECT 'admin.datacenter_created','reason','string' UNION ALL
    SELECT 'admin.datacenter_created','ts','timestamp' UNION ALL
    SELECT 'admin.datacenter_updated','id','id' UNION ALL
    SELECT 'admin.datacenter_updated','display_name','string' UNION ALL
    SELECT 'admin.datacenter_updated','operator','id' UNION ALL
    SELECT 'admin.datacenter_updated','reason','string' UNION ALL
    SELECT 'admin.datacenter_updated','ts','timestamp' UNION ALL
    SELECT 'admin.datacenter_deleted','id','id' UNION ALL
    SELECT 'admin.datacenter_deleted','display_name','string' UNION ALL
    SELECT 'admin.datacenter_deleted','operator','id' UNION ALL
    SELECT 'admin.datacenter_deleted','reason','string' UNION ALL
    SELECT 'admin.datacenter_deleted','ts','timestamp'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('admin','admin.device_activated','OpsDeviceService','E5/A2/A4/L3/H3','REGISTERED',
   'migration:e5-device-operations','E5 trusted activation contract consumed by H3',0),
  ('admin','admin.device_paused','OpsDeviceService','E5/A2/A4/L3','REGISTERED',
   'migration:e5-device-operations','E5 user scoped pause contract',0),
  ('admin','admin.device_resumed','OpsDeviceService','E5/A2/A4/L3','REGISTERED',
   'migration:e5-device-operations','E5 user scoped resume contract',0),
  ('admin','admin.device_deactivated','OpsDeviceService','E5/A2/A4/L3','REGISTERED',
   'migration:e5-device-operations','E5 manual deactivation contract',0),
  ('admin','admin.datacenter_created','OpsDeviceService','E5/A2/A4','REGISTERED',
   'migration:e5-device-operations','E5 datacenter creation contract',0),
  ('admin','admin.datacenter_updated','OpsDeviceService','E5/A2/A4','REGISTERED',
   'migration:e5-device-operations','E5 datacenter update contract',0),
  ('admin','admin.datacenter_deleted','OpsDeviceService','E5/A2/A4','REGISTERED',
   'migration:e5-device-operations','E5 datacenter deletion contract',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,57)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,57);

-- E5 高敏 force/unbind 只能由 A2 maker-checker 回放执行。GROWTH 是设备运营角色，
-- 只授予创建/查看提案的最小权限和 A2 菜单；审批/执行权仍只由既有 SUPER_ADMIN 持有。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT role_row.id, permission_row.id
  FROM nx_admin_role role_row
  JOIN nx_admin_permission permission_row
    ON permission_row.permission_code IN ('platform_a2_read', 'platform_a2_proposal_create')
 WHERE role_row.role_code = 'GROWTH'
   AND role_row.status = 1 AND role_row.is_deleted = 0
   AND permission_row.status = 1 AND permission_row.is_deleted = 0;

UPDATE nx_admin_role_permission role_permission
JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
   SET role_permission.is_deleted = 0
 WHERE role_row.role_code = 'GROWTH'
   AND permission_row.permission_code IN ('platform_a2_read', 'platform_a2_proposal_create');

INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT role_row.id, menu_row.id
  FROM nx_admin_role role_row
  JOIN nx_admin_menu menu_row ON menu_row.menu_code IN ('A', 'A2')
 WHERE role_row.role_code = 'GROWTH'
   AND role_row.status = 1 AND role_row.is_deleted = 0
   AND menu_row.status = 1 AND menu_row.is_deleted = 0;

UPDATE nx_admin_role_menu role_menu
JOIN nx_admin_role role_row ON role_row.id = role_menu.role_id
JOIN nx_admin_menu menu_row ON menu_row.id = role_menu.menu_id
   SET role_menu.is_deleted = 0
 WHERE role_row.role_code = 'GROWTH'
   AND menu_row.menu_code IN ('A', 'A2');

-- Maker 与 executor 分离：GROWTH 只能在 E 域创建/查看待确认提案，不能直调 E5 mutation。
UPDATE nx_admin_role_permission role_permission
JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
   SET role_permission.is_deleted = 1
 WHERE role_row.role_code = 'GROWTH'
   AND permission_row.permission_code IN (
       'device_e5_write',
       'device_e5_device_force_activate',
       'device_e5_device_unbind',
       'device_e5_datacenter_pause'
   );
