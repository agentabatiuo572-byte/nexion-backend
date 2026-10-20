-- Durable E6 receipts are global policy commands, not fictitious user commands.
CREATE TABLE IF NOT EXISTS nx_phone_policy_operation (
 operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 actor_id BIGINT NOT NULL, command_type VARCHAR(48) NOT NULL,
 idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, permission_code VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL, submitted_at BIGINT NOT NULL, completed_at BIGINT NULL,
 original_result_json JSON NULL, audit_ref VARCHAR(128) NULL,
 UNIQUE KEY uk_phone_policy_command(actor_id,command_type,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A human confirmation of an owned support record, never an inferred ticket status.
CREATE TABLE IF NOT EXISTS nx_phone_replacement_verification (
 evidence_ref VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,source_environment VARCHAR(16) NOT NULL,run_id VARCHAR(96) NOT NULL DEFAULT '',
 request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,target_installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 binding_version BIGINT NOT NULL,support_ref VARCHAR(128) NOT NULL,
 verified_by BIGINT NOT NULL,verified_at BIGINT NOT NULL,reason VARCHAR(200) NOT NULL,
 idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,request_hash CHAR(64) NOT NULL,
 UNIQUE KEY uk_phone_verification_key(verified_by,idempotency_key),
 KEY idx_phone_verification_request(user_id,request_id,verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing roles have no distinct security/risk lead. Never infer that RISK means lead.
-- A1 may explicitly assign these fine permissions to qualified administrators later.
INSERT IGNORE INTO nx_admin_permission
(permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
('user_c5_phone_replacement_exception_grant','C5 手机换机特批签发与申请关闭','API','/users/security','HIGH',0,1,0),
('user_c5_phone_replacement_exception_revoke','C5 手机换机特批撤销','API','/users/security','HIGH',0,1,0);
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND r.status=1 AND r.is_deleted=0
AND p.permission_code IN ('user_c5_phone_replacement_exception_grant','user_c5_phone_replacement_exception_revoke')
AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_event_schema_registry
(event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES ('admin.phone_replacement_policy_updated','admin','phone-policy','server','E6/A2/A4/K1/K4',1,'100%',1,'ACTIVE','migration:phone-policy','Versioned phone policy publication',0);
INSERT IGNORE INTO nx_event_schema_property(schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
FROM nx_event_schema_registry s CROSS JOIN (
 SELECT 'operation_id' property_name,'id' property_type UNION ALL SELECT 'operator_id','id'
 UNION ALL SELECT 'old_policy','json' UNION ALL SELECT 'new_policy','json' UNION ALL SELECT 'reason','string'
) p WHERE s.event_name='admin.phone_replacement_policy_updated';

-- E6 phone policy batches include the existing switch authority. Some canonical
-- startup baselines predate its separate E6 closure migration; insert only if absent.
INSERT IGNORE INTO nx_admin_permission
(permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES ('device_e6_flag_toggle','E6 电脑算力与手机换绑开关','API','/devices/compute-config','HIGH',0,1,0);
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND r.status=1 AND r.is_deleted=0
AND p.permission_code='device_e6_flag_toggle' AND p.status=1 AND p.is_deleted=0;
