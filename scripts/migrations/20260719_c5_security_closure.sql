-- C5 security closure: canonical login lock, forced-reset flag, exact RBAC and A4 events.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_security' AND COLUMN_NAME='password_reset_required')=0,
  'ALTER TABLE nx_user_security ADD COLUMN password_reset_required TINYINT NOT NULL DEFAULT 0 AFTER login_fail_count',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_security' AND COLUMN_NAME='password_changed_at')=0,
  'ALTER TABLE nx_user_security ADD COLUMN password_changed_at DATETIME NULL AFTER password_reset_required',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_session' AND COLUMN_NAME='session_chain_id')=0,
  'ALTER TABLE nx_user_session ADD COLUMN session_chain_id VARCHAR(64) NULL AFTER refresh_token_id, ADD COLUMN rotated_to_id VARCHAR(96) NULL AFTER session_chain_id, ADD COLUMN rotation_redeemed_at DATETIME NULL AFTER rotated_to_id, ADD INDEX idx_user_session_chain (session_chain_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_user_session SET session_chain_id=COALESCE(session_chain_id,refresh_token_id)
 WHERE is_deleted=0 AND session_chain_id IS NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_session' AND COLUMN_NAME='last_active_at')=0,
  'ALTER TABLE nx_user_session ADD COLUMN last_active_at DATETIME NULL AFTER revoked_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_user_session SET last_active_at=COALESCE(last_active_at,updated_at,created_at)
 WHERE is_deleted=0 AND last_active_at IS NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_login_guard' AND COLUMN_NAME='user_id')=0,
  'ALTER TABLE nx_user_login_guard ADD COLUMN user_id BIGINT NULL AFTER login_key',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_login_guard' AND INDEX_NAME='idx_user_login_guard_user')=0,
  'ALTER TABLE nx_user_login_guard ADD INDEX idx_user_login_guard_user (user_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Bind already-created authenticated security guards so PC C5 and App share one lock truth.
UPDATE nx_user_login_guard g
JOIN nx_user u ON g.login_key=SHA2(CONCAT('security-password:',u.id),256)
   SET g.user_id=u.id
 WHERE g.user_id IS NULL AND u.is_deleted=0;

-- Preserve legacy forced-reset state while future requests keep the password hash intact.
INSERT INTO nx_user_security
  (user_id,two_factor_enabled,login_fail_count,password_reset_required,created_at,updated_at,is_deleted)
SELECT u.id,0,0,1,NOW(),NOW(),0
  FROM nx_user u
 WHERE u.is_deleted=0 AND u.password_hash LIKE 'RESET_REQUIRED$%'
ON DUPLICATE KEY UPDATE password_reset_required=1,updated_at=NOW(),is_deleted=0;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('auth.session.access_ttl_hours','4','NUMBER','auth','ADMIN','C5 access token TTL; 1-24 hours',1,0),
  ('auth.session.refresh_ttl_days','30','NUMBER','auth','ADMIN','C5 refresh session TTL; 7-90 days',1,0),
  ('auth.session.idle_ttl_days','30','NUMBER','auth','ADMIN','C5 idle session TTL; 7-90 days',1,0),
  ('auth.session.step_up_days','7','NUMBER','auth','ADMIN','C5 step-up verification remember window; 1-30 days',1,0),
  ('auth.risk.login_lock_threshold','5','NUMBER','auth','ADMIN','C5 short-lock failed-login threshold',1,0),
  ('auth.risk.lock_duration_minutes','15','NUMBER','auth','ADMIN','C5 short-lock duration',1,0),
  ('auth.risk.login_long_lock_threshold','10','NUMBER','auth','ADMIN','C5 long-lock failed-login threshold',1,0),
  ('auth.risk.long_lock_duration_hours','24','NUMBER','auth','ADMIN','C5 long-lock duration',1,0)
ON DUPLICATE KEY UPDATE value_type='NUMBER',config_group='auth',visibility='ADMIN',
  remark=VALUES(remark),status=1,is_deleted=0;

CREATE TABLE IF NOT EXISTS nx_c5_kyc_reverification_consumption (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  action_code VARCHAR(48) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  consumed_by VARCHAR(64) NOT NULL,
  consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_c5_kyc_reverification_ticket (ticket_id),
  UNIQUE KEY uk_c5_kyc_reverification_idempotency (action_code,user_id,idempotency_key),
  KEY idx_c5_kyc_reverification_user (user_id,action_code,consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('user_c5_session_revoke_one','C5 踢线(单会话)','API','/users/security','HIGH',0,1,0),
  ('user_c5_session_revoke_all','C5 全部踢线','API','/users/security','HIGH',0,1,0),
  ('user_c5_unlock_short','C5 解除短锁','API','/users/security','HIGH',0,1,0),
  ('user_c5_unlock_long','C5 解除长锁','API','/users/security','HIGH',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type),amplifies=VALUES(amplifies),status=1,is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT')
  AND p.permission_code IN ('user_c5_session_revoke_one','user_c5_session_revoke_all','user_c5_unlock_short','user_c5_unlock_long');

INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT')
   AND p.permission_code IN ('user_c5_session_revoke_one','user_c5_session_revoke_all')
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT')
   AND p.permission_code='user_c5_unlock_short'
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','RISK')
   AND p.permission_code='user_c5_unlock_long'
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.2fa_disabled','admin','phase_admin','server','A2/B5/C5/c2-high-risk-admin-alert',1,'100%',34,'ACTIVE','migration:c5','C5 authoritative two-factor disable',0),
  ('admin.password_reset_requested','admin','phase_admin','server','A2/B5/C5/c2-high-risk-admin-alert',1,'100%',35,'ACTIVE','migration:c5','C5 authoritative forced-password-reset request',0),
  ('admin.user_unlocked','admin','phase_admin','server','A2/B5/C5/c2-high-risk-admin-alert',1,'100%',36,'ACTIVE','migration:c5','C5 authoritative short or long unlock',0),
  ('admin.session_revoked','admin','phase_admin','server','A2/B5/C5/c2-high-risk-admin-alert',1,'100%',37,'ACTIVE','migration:c5','C5 authoritative session revocation',0),
  ('auth.login_locked','auth','risk','server','B5/C5/c2-high-risk-admin-alert',1,'100%',38,'ACTIVE','migration:c5','C5 canonical user login lock',0),
  ('auth.refresh_token_reuse_detected','auth','risk','server','B5/C5/c2-high-risk-admin-alert',1,'100%',39,'ACTIVE','migration:c5','C5 refresh credential reuse and chain revocation',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer='server',
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1
 WHERE s.event_name IN ('admin.2fa_disabled','admin.password_reset_requested','admin.user_unlocked','admin.session_revoked',
                        'auth.login_locked','auth.refresh_token_reuse_detected');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'target_user_id' property_name,'id' property_type,1 required_field UNION ALL
  SELECT 'operator','id',1 UNION ALL
  SELECT 'role','enum',1 UNION ALL
  SELECT 'reason','string',1 UNION ALL
  SELECT 'kyc_verification_result','enum',0 UNION ALL
  SELECT 'lock_kind','enum',0 UNION ALL
  SELECT 'scope','enum',0 UNION ALL
  SELECT 'occurred_at','timestamp',1
) p
WHERE s.event_name IN ('admin.2fa_disabled','admin.password_reset_requested','admin.user_unlocked','admin.session_revoked')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'target_user_id' property_name,'id' property_type,1 required_field UNION ALL
  SELECT 'login_key_hash','string',1 UNION ALL
  SELECT 'lock_type','enum',1 UNION ALL
  SELECT 'rule_id','string',1 UNION ALL
  SELECT 'failed_count','number',1 UNION ALL
  SELECT 'locked_until','timestamp',1 UNION ALL
  SELECT 'occurred_at','timestamp',1
) p
WHERE s.event_name='auth.login_locked'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'target_user_id' property_name,'id' property_type,1 required_field UNION ALL
  SELECT 'session_chain_id','id',1 UNION ALL
  SELECT 'detected_at','timestamp',1
) p
WHERE s.event_name='auth.refresh_token_reuse_detected'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,39)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,39);
