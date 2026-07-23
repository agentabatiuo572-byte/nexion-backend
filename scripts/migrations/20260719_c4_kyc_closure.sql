-- C4 KYC closure: authoritative ledger, CAS history, exact RBAC, K5/L5 links and A4 events.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_kyc_profile' AND COLUMN_NAME='paired_address')=0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN paired_address VARCHAR(255) NULL AFTER risk_notes', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_kyc_profile' AND COLUMN_NAME='network')=0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN network VARCHAR(32) NULL AFTER paired_address', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_kyc_profile' AND COLUMN_NAME='paired_at')=0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN paired_at DATETIME NULL AFTER network', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_kyc_profile' AND COLUMN_NAME='trigger_source')=0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN trigger_source VARCHAR(64) NOT NULL DEFAULT ''LEGACY_MIGRATION'' AFTER paired_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_kyc_profile' AND COLUMN_NAME='version')=0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER trigger_source', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO nx_kyc_profile
  (user_id,kyc_no,status,country,paired_address,network,paired_at,trigger_source,version,is_deleted)
SELECT u.id,CONCAT('KYC-LEGACY-',IF(u.id < 100000000,LPAD(u.id,8,'0'),CAST(u.id AS CHAR))),UPPER(COALESCE(NULLIF(u.kyc_status,''),'NONE')),
       u.country_code,NULLIF(p.wallet_address,''),NULL,NULL,'LEGACY_MIGRATION',0,0
  FROM nx_user u
  LEFT JOIN nx_user_profile p ON p.user_id=u.id AND p.is_deleted=0
 WHERE u.is_deleted=0
ON DUPLICATE KEY UPDATE
  paired_address=COALESCE(nx_kyc_profile.paired_address,VALUES(paired_address)),
  status=COALESCE(NULLIF(nx_kyc_profile.status,''),VALUES(status)),
  country=COALESCE(nx_kyc_profile.country,VALUES(country)),is_deleted=0;

CREATE TABLE IF NOT EXISTS nx_kyc_status_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  before_status VARCHAR(32) NULL,
  after_status VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  evidence_ref VARCHAR(255) NOT NULL,
  source VARCHAR(64) NOT NULL,
  operator VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  ticket_id VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_kyc_history_idempotency (idempotency_key),
  KEY idx_kyc_history_user_time (user_id,created_at),
  KEY idx_kyc_history_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO nx_kyc_status_history
  (user_id,before_status,after_status,reason_code,reason,evidence_ref,source,operator,idempotency_key)
SELECT user_id,NULL,status,'LEGACY_MIGRATION','Historical status imported without fabricated review facts',
       CONCAT('migration:nx_kyc_profile:',id),'LEGACY_MIGRATION','migration:c4',CONCAT('legacy:c4:',user_id)
  FROM nx_kyc_profile WHERE is_deleted=0;

-- Every future account receives the C4 authority row at creation time. Cross-domain
-- consumers fail closed when this invariant is violated; they never read nx_user.kyc_status.
DROP TRIGGER IF EXISTS trg_nx_user_kyc_profile;
CREATE TRIGGER trg_nx_user_kyc_profile
AFTER INSERT ON nx_user
FOR EACH ROW
INSERT INTO nx_kyc_profile
  (user_id,kyc_no,status,country,trigger_source,version,is_deleted)
VALUES
  (NEW.id,CONCAT('KYC-',IF(NEW.id < 100000000,LPAD(NEW.id,8,'0'),CAST(NEW.id AS CHAR))),UPPER(COALESCE(NULLIF(NEW.kyc_status,''),'NONE')),
   NEW.country_code,'REGISTRATION',0,0);

UPDATE nx_kyc_status_history
   SET reason='历史状态迁入，仅保留可核对的原始状态，不补造审核事实'
 WHERE source='LEGACY_MIGRATION'
   AND reason='Historical status imported without fabricated review facts';

UPDATE nx_admin_fourth_batch_report
   SET last_action_at=COALESCE(last_action_at,created_at)
 WHERE module_code='L5' AND report_type='KYC_REGULATORY' AND is_deleted=0;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('kyc.network_whitelist','TRC20 / ERC20 / BTC / ETH','STRING','kyc','ADMIN','C4 wallet pairing networks; exact allow-list',1,0)
ON DUPLICATE KEY UPDATE value_type='STRING',config_group='kyc',visibility='ADMIN',
  remark='C4 wallet pairing networks; exact allow-list',status=1,is_deleted=0;

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('user_c4_read','C4 KYC合规台账-页面读','API','/users/kyc','READ',0,1,0),
  ('user_c4_verify','C4 人工标记实名通过','API','/users/kyc','HIGH',1,1,0),
  ('user_c4_revoke','C4 撤销实名','API','/users/kyc','HIGH',0,1,0),
  ('user_c4_trigger_review','C4 触发K5增强复审','API','/users/kyc','HIGH',0,1,0),
  ('user_c4_export','C4 监管脱敏导出','API','/users/kyc','HIGH',0,1,0),
  ('user_c4_network_write','C4 配对网络白名单','API','/users/kyc','HIGH',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type),amplifies=VALUES(amplifies),status=1,is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code IN ('user_c4_write','user_c4_kyc_revoke','user_c4_read','user_c4_verify','user_c4_revoke','user_c4_trigger_review','user_c4_export','user_c4_network_write');
UPDATE nx_admin_permission SET status=0,is_deleted=1,updated_at=NOW()
 WHERE permission_code IN ('user_c4_write','user_c4_kyc_revoke');

INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT','AUDITOR','FINANCE','FINANCE_LEAD')
   AND p.permission_code='user_c4_read' AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','RISK')
   AND p.permission_code IN ('user_c4_verify','user_c4_revoke','user_c4_network_write')
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT')
   AND p.permission_code='user_c4_trigger_review'
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','RISK','AUDITOR','FINANCE','FINANCE_LEAD')
   AND p.permission_code='user_c4_export'
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_menu (role_id,menu_id)
SELECT r.id,m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.route_path='/users/kyc' AND m.is_deleted=0
 WHERE r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT','AUDITOR','FINANCE','FINANCE_LEAD') AND r.status=1 AND r.is_deleted=0;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.kyc_status_changed','admin','phase_admin','server','C4/K5/D2/G2/A2',1,'100%',31,'ACTIVE','migration:c4','Authoritative KYC status transition',0),
  ('risk.kyc_review_triggered','risk','risk','server','C4/K5/A2',1,'100%',32,'ACTIVE','migration:c4','K5 review created or merged from C4',0),
  ('admin.kyc_export_created','admin','phase_admin','server','C4/L5/A2',1,'100%',33,'ACTIVE','migration:c4','Persisted masked regulatory export',0)
ON DUPLICATE KEY UPDATE status='ACTIVE',is_deleted=0,current_revision=VALUES(current_revision);

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'target_user_id' property_name,'id' property_type,0 required_field UNION ALL
  SELECT 'from_status','enum',0 UNION ALL SELECT 'to_status','enum',0 UNION ALL
  SELECT 'reason_code','enum',0 UNION ALL SELECT 'evidence_ref','string',0 UNION ALL
  SELECT 'ticket_id','id',0 UNION ALL SELECT 'created','boolean',0 UNION ALL
  SELECT 'job_no','id',0 UNION ALL SELECT 'scope','enum',0 UNION ALL
  SELECT 'row_count','number',0 UNION ALL SELECT 'masked','boolean',0 UNION ALL
  SELECT 'operator','id',1 UNION ALL SELECT 'source','enum',1 UNION ALL
  SELECT 'occurred_at','timestamp',1
) p
WHERE s.event_name IN ('admin.kyc_status_changed','risk.kyc_review_triggered','admin.kyc_export_created')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,33)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,33);
