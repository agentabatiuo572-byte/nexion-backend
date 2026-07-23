-- C3 balance adjustment closure: immediate posting, durable idempotency/reversal,
-- exact RBAC, and canonical A4 event registration.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_asset_adjustment' AND COLUMN_NAME='amount_usd')=0,
  'ALTER TABLE nx_wallet_asset_adjustment ADD COLUMN amount_usd DECIMAL(24,8) NULL AFTER amount', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_wallet_asset_adjustment SET amount_usd=amount WHERE amount_usd IS NULL;
ALTER TABLE nx_wallet_asset_adjustment MODIFY amount_usd DECIMAL(24,8) NOT NULL;
SET @c3_nex_rate = COALESCE(
  (SELECT CAST(config_value AS DECIMAL(24,8)) FROM nx_config_item
    WHERE config_key='wallet.exchange.nex_usdt_price' AND status=1 AND is_deleted=0 ORDER BY id DESC LIMIT 1),
  (SELECT price_usdt FROM nx_price_index
    WHERE status='ACTIVE' AND is_deleted=0 AND metric_code IN ('NEX','NEX_USDT') ORDER BY sampled_at DESC,id DESC LIMIT 1),
  0);
UPDATE nx_wallet_asset_adjustment
   SET amount_usd = CASE WHEN UPPER(asset)='USDT' THEN amount ELSE ROUND(amount * @c3_nex_rate, 8) END;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_asset_adjustment' AND COLUMN_NAME='evidence_ref')=0,
  'ALTER TABLE nx_wallet_asset_adjustment ADD COLUMN evidence_ref VARCHAR(255) NULL AFTER reason', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_wallet_asset_adjustment SET evidence_ref=CONCAT('legacy:', adjustment_no) WHERE evidence_ref IS NULL OR evidence_ref='';
ALTER TABLE nx_wallet_asset_adjustment MODIFY evidence_ref VARCHAR(255) NOT NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_asset_adjustment' AND COLUMN_NAME='idempotency_key')=0,
  'ALTER TABLE nx_wallet_asset_adjustment ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER evidence_ref', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_wallet_asset_adjustment SET idempotency_key=CONCAT('legacy:', adjustment_no) WHERE idempotency_key IS NULL OR idempotency_key='';
ALTER TABLE nx_wallet_asset_adjustment MODIFY idempotency_key VARCHAR(128) NOT NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_asset_adjustment' AND COLUMN_NAME='reversal_of')=0,
  'ALTER TABLE nx_wallet_asset_adjustment ADD COLUMN reversal_of VARCHAR(64) NULL AFTER idempotency_key', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_asset_adjustment' AND INDEX_NAME='uk_wallet_asset_adjustment_idempotency')=0,
  'ALTER TABLE nx_wallet_asset_adjustment ADD UNIQUE KEY uk_wallet_asset_adjustment_idempotency (idempotency_key)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_asset_adjustment' AND INDEX_NAME='uk_wallet_asset_adjustment_reversal')=0,
  'ALTER TABLE nx_wallet_asset_adjustment ADD UNIQUE KEY uk_wallet_asset_adjustment_reversal (reversal_of)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Remove old broad C3 mutations, then grant only the product matrix.
INSERT INTO nx_admin_role (role_code,role_name,remark,status,is_deleted)
VALUES ('FINANCE_LEAD','财务主管','财务权限并负责大额调整与冲正',1,0)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),remark=VALUES(remark),status=1,is_deleted=0,updated_at=NOW();
INSERT IGNORE INTO nx_admin_role_menu (role_id,menu_id)
SELECT lead_role.id,rm.menu_id FROM nx_admin_role lead_role
JOIN nx_admin_role finance_role ON finance_role.role_code='FINANCE' AND finance_role.is_deleted=0
JOIN nx_admin_role_menu rm ON rm.role_id=finance_role.id AND rm.is_deleted=0
WHERE lead_role.role_code='FINANCE_LEAD' AND lead_role.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT lead_role.id,rp.permission_id FROM nx_admin_role lead_role
JOIN nx_admin_role finance_role ON finance_role.role_code='FINANCE' AND finance_role.is_deleted=0
JOIN nx_admin_role_permission rp ON rp.role_id=finance_role.id AND rp.is_deleted=0
WHERE lead_role.role_code='FINANCE_LEAD' AND lead_role.is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code <> 'SUPER_ADMIN'
  AND p.permission_code IN ('user_c3_write','user_c3_adjust_create','user_c3_adjust_approve','user_c3_adjust_reverse');
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','SUPPORT','AUDITOR')
  AND p.permission_code='user_c3_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','SUPPORT')
  AND p.permission_code='user_c3_adjust_create' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('user_c3_adjust_approve','user_c3_adjust_reverse')
  AND p.status=1 AND p.is_deleted=0;

-- D4 是账单审计读模型；所有余额纠错统一走 C3 的校验、幂等、审计和事件闭环。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='finance_d4_adjustment_create';
UPDATE nx_admin_permission
SET status=0, is_deleted=1, updated_at=NOW()
WHERE permission_code='finance_d4_adjustment_create';

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('admin.balance_adjusted','admin','phase_admin','server','C3/D4/A2',1,'100%',29,'ACTIVE','migration:c3','C3 wallet balance adjustment',0),
  ('admin.bill_adjusted','admin','phase_admin','server','C3/D4/A2',1,'100%',30,'ACTIVE','migration:c3','C3 ledger bill adjustment',0)
ON DUPLICATE KEY UPDATE status='ACTIVE', is_deleted=0, current_revision=VALUES(current_revision);

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, p.required_field, s.current_revision, 0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'target_user_id' property_name,'id' property_type,1 required_field UNION ALL
  SELECT 'adjustment_no','id',1 UNION ALL SELECT 'bill_id','id',1 UNION ALL
  SELECT 'asset','enum',1 UNION ALL SELECT 'direction','enum',1 UNION ALL
  SELECT 'amount','number',1 UNION ALL SELECT 'amount_usd','number',1 UNION ALL
  SELECT 'balance_after','number',1 UNION ALL SELECT 'reason_code','enum',1 UNION ALL
  SELECT 'operator','id',1 UNION ALL SELECT 'occurred_at','timestamp',1 UNION ALL
  SELECT 'reversal_of','id',0
) p
WHERE s.event_name IN ('admin.balance_adjusted','admin.bill_adjusted')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type), required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,30)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,30);
