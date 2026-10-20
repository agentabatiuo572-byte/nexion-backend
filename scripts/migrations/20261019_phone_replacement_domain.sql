-- Domain facts only. Never infer a replacement count or a reliable collection start from this DDL.
-- These inserts preserve every explicitly stored legacy value, including interval zero.
INSERT IGNORE INTO nx_config_item
(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
('E.compute.phoneBinding.allowReplacement','off','STRING','e6_compute','ADMIN','Phone replacement switch',1,0),
('E.compute.phoneBinding.minReplacementIntervalDays','1','STRING','e6_compute','ADMIN','New configuration interval in days',1,0),
('E.compute.phoneBinding.replacementWindowDays','30','STRING','e6_compute','ADMIN','Rolling replacement window in days',1,0),
('E.compute.phoneBinding.maxReplacementsInWindow','2','STRING','e6_compute','ADMIN','Successful replacements in rolling window',1,0),
('E.compute.phoneBinding.policyVersion','1','NUMBER','e6_compute','ADMIN','Phone policy revision metadata',1,0);

CREATE TABLE IF NOT EXISTS nx_phone_replacement_history_state (
 user_id BIGINT NOT NULL, source_environment VARCHAR(16) NOT NULL, run_id VARCHAR(96) NOT NULL DEFAULT '',
 history_complete_from BIGINT NULL, all_history_known TINYINT NOT NULL DEFAULT 0,
 changed_at_verified TINYINT NOT NULL DEFAULT 0, evidence_ref VARCHAR(200) NULL,
 PRIMARY KEY(user_id,source_environment,run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_phone_replacement_operation (
 operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL, source_environment VARCHAR(16) NOT NULL, run_id VARCHAR(96) NOT NULL DEFAULT '',
 actor_type VARCHAR(16) NOT NULL, actor_id BIGINT NOT NULL, command_type VARCHAR(48) NOT NULL,
 idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, request_hash CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, submitted_at BIGINT NOT NULL, completed_at BIGINT NULL,
 original_result_json JSON NULL, resource_type VARCHAR(32) NULL, resource_id VARCHAR(64) NULL, audit_ref VARCHAR(128) NULL,
 UNIQUE KEY uk_phone_operation_key(source_environment,run_id,actor_type,actor_id,command_type,idempotency_key),
 KEY idx_phone_operation_account(user_id,source_environment,run_id,submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_phone_replacement_event (
 event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL, source_environment VARCHAR(16) NOT NULL, run_id VARCHAR(96) NOT NULL DEFAULT '',
 operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 old_installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 new_installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 old_user_device_id BIGINT NOT NULL, new_user_device_id BIGINT NOT NULL,
 old_binding_version BIGINT NOT NULL, new_binding_version BIGINT NOT NULL,
 committed_at BIGINT NOT NULL, policy_version BIGINT NOT NULL, grant_id VARCHAR(64) NULL,
 source_kind VARCHAR(32) NOT NULL DEFAULT 'LIVE', source_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 UNIQUE KEY uk_phone_event_operation(operation_id),
 UNIQUE KEY uk_phone_event_source(user_id,source_environment,run_id,source_kind,source_id),
 KEY idx_phone_event_window(user_id,source_environment,run_id,committed_at,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_phone_replacement_request (
 request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 request_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL, source_environment VARCHAR(16) NOT NULL, run_id VARCHAR(96) NOT NULL DEFAULT '',
 target_installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, binding_version BIGINT NOT NULL,
 reason VARCHAR(200) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL, grant_id VARCHAR(64) NULL, create_operation_id VARCHAR(64) NOT NULL,
 support_ref VARCHAR(128) NULL, support_link_status VARCHAR(16) NOT NULL DEFAULT 'UNLINKED',
 close_reason VARCHAR(200) NULL, closed_at BIGINT NULL,
 UNIQUE KEY uk_phone_request_no(request_no), UNIQUE KEY uk_phone_request_operation(create_operation_id),
 KEY idx_phone_request_current(user_id,source_environment,run_id,status,target_installation_id,binding_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_phone_replacement_grant (
 grant_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL, source_environment VARCHAR(16) NOT NULL, run_id VARCHAR(96) NOT NULL DEFAULT '',
 target_installation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, binding_version BIGINT NOT NULL,
 waive_count TINYINT NOT NULL, waive_interval TINYINT NOT NULL,
 issued_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL,
 reason VARCHAR(200) NOT NULL, evidence_json JSON NOT NULL, operator_id BIGINT NOT NULL,
 issue_operation_id VARCHAR(64) NOT NULL, consumed_operation_id VARCHAR(64) NULL,
 consumed_at BIGINT NULL, terminal_at BIGINT NULL,
 UNIQUE KEY uk_phone_grant_request(request_id), UNIQUE KEY uk_phone_grant_operation(issue_operation_id),
 UNIQUE KEY uk_phone_grant_consumption(consumed_operation_id),
 KEY idx_phone_grant_active(user_id,source_environment,run_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- Required A4 schemas for the domain producer. Success counts remain in the dedicated fact table.
INSERT IGNORE INTO nx_event_schema_registry
(event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,sampling_policy,current_revision,status,created_by,reason,is_deleted)
SELECT e.event_name,IF(e.event_name LIKE 'admin.%','admin','phone'),'phone-replacement','server','C5/A2/A4/K1/K4',1,'100%',1,'ACTIVE','migration:phone-replacement','Phone replacement domain facts',0
FROM (
 SELECT 'phone.replacement_succeeded' event_name UNION ALL SELECT 'phone.replacement_rejected'
 UNION ALL SELECT 'phone.replacement_request_created' UNION ALL SELECT 'admin.phone_replacement_request_closed'
 UNION ALL SELECT 'admin.phone_replacement_grant_issued' UNION ALL SELECT 'admin.phone_replacement_grant_revoked'
 UNION ALL SELECT 'admin.phone_replacement_grant_consumed' UNION ALL SELECT 'admin.phone_replacement_grant_expired'
 UNION ALL SELECT 'admin.phone_replacement_grant_stale' UNION ALL SELECT 'admin.phone_replacement_grant_rejected'
) e;
INSERT IGNORE INTO nx_event_schema_property(schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
FROM nx_event_schema_registry s CROSS JOIN (
 SELECT 'resource_id' property_name,'id' property_type,1 required_field UNION ALL
 SELECT 'operation_id','id',0 UNION ALL SELECT 'details','json',1
) p WHERE s.family_key='phone-replacement' AND s.created_by='migration:phone-replacement';
