-- D2 withdrawal review closure: CAS version, exact permissions and A4 money schemas.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_version')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_version BIGINT NOT NULL DEFAULT 0 AFTER c2_frozen_by_user_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- D5 is the single authority for the NEX fee offset. Keep the wallet mirror for
-- downstream producers, while the canonical key wins when both are present.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('withdrawal.nex_fee_offset_rate','0.40','NUMBER','wallet','ADMIN','D5 canonical NEX fee offset rate (USDT per NEX)',1,0),
  ('wallet.withdrawal.nex_fee_offset_rate','0.40','NUMBER','wallet','ADMIN','D5 NEX fee offset downstream mirror',1,0)
ON DUPLICATE KEY UPDATE value_type='NUMBER', config_group='wallet', visibility='ADMIN',
  remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_hold_until')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_hold_until DATETIME NULL AFTER d2_version',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_penalty_fee_rate')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_penalty_fee_rate DECIMAL(10,6) NULL AFTER d2_hold_until',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_gross_fee')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_gross_fee DECIMAL(18,6) NULL AFTER d2_penalty_fee_rate',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_nex_burned')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_nex_burned DECIMAL(18,6) NULL AFTER d2_gross_fee',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_nex_fee_offset_rate')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_nex_fee_offset_rate DECIMAL(18,6) NULL AFTER d2_nex_burned',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_fee_waived')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_fee_waived DECIMAL(18,6) NULL AFTER d2_nex_fee_offset_rate',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_actual_fee')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_actual_fee DECIMAL(18,6) NULL AFTER d2_fee_waived',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_net_receive')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_net_receive DECIMAL(18,6) NULL AFTER d2_actual_fee',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Legacy records did not snapshot the D5 fee facts. Preserve the historical fee as gross/actual,
-- make the zero NEX discount explicit, and use the D5 default rate only as an audit reference.
UPDATE nx_withdrawal_order
   SET d2_penalty_fee_rate = COALESCE(d2_penalty_fee_rate, ROUND(COALESCE(fee,0)/NULLIF(amount,0)*100,6), 0),
       d2_gross_fee = COALESCE(d2_gross_fee, fee, 0),
       d2_nex_burned = COALESCE(d2_nex_burned, 0),
       d2_nex_fee_offset_rate = COALESCE(d2_nex_fee_offset_rate, 0.4),
       d2_fee_waived = COALESCE(d2_fee_waived,
         LEAST(COALESCE(d2_gross_fee,fee,0), COALESCE(d2_nex_burned,0)*COALESCE(d2_nex_fee_offset_rate,0.4))),
       d2_actual_fee = COALESCE(d2_actual_fee,
         GREATEST(COALESCE(d2_gross_fee,fee,0)-COALESCE(d2_fee_waived,0),0)),
       d2_net_receive = COALESCE(d2_net_receive,
         GREATEST(amount-COALESCE(d2_actual_fee,COALESCE(d2_gross_fee,fee,0)),0))
;

-- Every producer, including producers outside this module, persists immutable D5 facts at INSERT time.
-- Explicit upstream snapshots win; legacy producers receive a deterministic, auditable zero-burn snapshot.
DROP TRIGGER IF EXISTS trg_withdrawal_d2_fee_snapshot_bi;
DELIMITER $$
CREATE TRIGGER trg_withdrawal_d2_fee_snapshot_bi
BEFORE INSERT ON nx_withdrawal_order
FOR EACH ROW
BEGIN
  DECLARE v_nex_fee_offset_rate DECIMAL(18,6) DEFAULT 0.400000;
  SELECT CAST(config_value AS DECIMAL(18,6))
    INTO v_nex_fee_offset_rate
    FROM nx_config_item
   WHERE config_key IN ('withdrawal.nex_fee_offset_rate','wallet.withdrawal.nex_fee_offset_rate')
     AND status=1 AND is_deleted=0
     AND CAST(config_value AS DECIMAL(18,6)) > 0
   ORDER BY (config_key='withdrawal.nex_fee_offset_rate') DESC, updated_at DESC
   LIMIT 1;
  SET NEW.d2_penalty_fee_rate = COALESCE(NEW.d2_penalty_fee_rate,
    ROUND(COALESCE(NEW.fee,0)/NULLIF(NEW.amount,0)*100,6), 0);
  SET NEW.d2_gross_fee = COALESCE(NEW.d2_gross_fee, NEW.fee, 0);
  SET NEW.d2_nex_burned = COALESCE(NEW.d2_nex_burned, 0);
  SET NEW.d2_nex_fee_offset_rate = COALESCE(NEW.d2_nex_fee_offset_rate, v_nex_fee_offset_rate, 0.4);
  SET NEW.d2_fee_waived = COALESCE(NEW.d2_fee_waived,
    LEAST(NEW.d2_gross_fee, NEW.d2_nex_burned*NEW.d2_nex_fee_offset_rate));
  SET NEW.d2_actual_fee = COALESCE(NEW.d2_actual_fee,
    GREATEST(NEW.d2_gross_fee-NEW.d2_fee_waived,0));
  SET NEW.d2_net_receive = COALESCE(NEW.d2_net_receive,
    GREATEST(NEW.amount-NEW.d2_actual_fee,0));
END$$
DELIMITER ;

ALTER TABLE nx_withdrawal_order
  MODIFY COLUMN d2_penalty_fee_rate DECIMAL(10,6) NOT NULL,
  MODIFY COLUMN d2_gross_fee DECIMAL(18,6) NOT NULL,
  MODIFY COLUMN d2_nex_burned DECIMAL(18,6) NOT NULL,
  MODIFY COLUMN d2_nex_fee_offset_rate DECIMAL(18,6) NOT NULL,
  MODIFY COLUMN d2_fee_waived DECIMAL(18,6) NOT NULL,
  MODIFY COLUMN d2_actual_fee DECIMAL(18,6) NOT NULL,
  MODIFY COLUMN d2_net_receive DECIMAL(18,6) NOT NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_lifecycle_owner')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_lifecycle_owner VARCHAR(128) NULL AFTER d2_hold_until',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_freeze_period')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_freeze_period VARCHAR(32) NULL AFTER d2_lifecycle_owner',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_previous_status')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_previous_status VARCHAR(32) NULL AFTER d2_freeze_period',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Persist one server-canonical lifecycle. Legacy rows are upgraded in place so that
-- extended holds cannot be approved or rejected before the scheduler releases them.
UPDATE nx_withdrawal_order
   SET d2_hold_until = CASE
         WHEN status='DELAYED' AND d2_hold_until IS NULL THEN DATE_ADD(NOW(), INTERVAL 7 DAY)
         ELSE d2_hold_until END,
       status = CASE status
     WHEN 'PENDING' THEN 'SUBMITTED'
     WHEN 'REVIEWING' THEN 'REVIEW_PENDING'
     WHEN 'DELAYED' THEN 'EXTENDED_HOLD'
     WHEN 'PENDING_CHAIN' THEN 'REVIEW_PASSED'
     WHEN 'CHAIN_SUBMITTED' THEN 'SENT'
     WHEN 'SUCCESS' THEN 'CONFIRMED'
     WHEN 'REJECTED' THEN 'REVIEW_REJECTED'
     WHEN 'FAILED' THEN 'TX_FAILED'
     WHEN 'DEAD' THEN 'TX_ORPHANED'
     ELSE status END
 WHERE is_deleted=0
   AND status IN ('PENDING','REVIEWING','DELAYED','PENDING_CHAIN','CHAIN_SUBMITTED','SUCCESS','REJECTED','FAILED','DEAD');

INSERT INTO nx_admin_role (role_code, role_name, remark, status, is_deleted)
VALUES ('RISK_LEAD','风控主管','D2 提现风险复核主管；不含资金退款权限',1,0)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('finance_d2_withdrawal_delay','提现延迟','API','/finance/withdrawals','HIGH',1,1,0),
  ('finance_d2_withdrawal_refund','提现手动退款','API','/finance/withdrawals','HIGH',1,1,0),
  ('finance_d2_withdrawal_batch','提现批量审核','API','/finance/withdrawals','HIGH',1,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type), amplifies=VALUES(amplifies), status=1, is_deleted=0, updated_at=NOW();

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','RISK_LEAD','SUPPORT','AUDITOR')
  AND p.permission_code LIKE 'finance_d2_%';
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND p.permission_code LIKE 'finance_d2_%' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','RISK_LEAD','SUPPORT','AUDITOR') AND p.permission_code='finance_d2_read'
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD') AND p.permission_code='finance_d2_withdrawal_approve'
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','RISK_LEAD')
  AND p.permission_code IN ('finance_d2_withdrawal_delay','finance_d2_withdrawal_reject','finance_d2_withdrawal_batch')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('finance_d2_withdrawal_freeze','finance_d2_withdrawal_unfreeze','finance_d2_withdrawal_refund')
  AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='RISK_LEAD'
  AND p.permission_code IN ('finance_d2_withdrawal_freeze','finance_d2_withdrawal_unfreeze')
  AND p.status=1 AND p.is_deleted=0;

-- Auditors must be able to discover the read-only D2 entry.
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id,m.id FROM nx_admin_role r JOIN nx_admin_menu m
WHERE r.role_code IN ('AUDITOR','SUPPORT','RISK_LEAD') AND m.menu_code IN ('D','D2') AND m.status=1 AND m.is_deleted=0;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('withdraw.approved','withdraw','money','server','D2/B1/B5/L',1,'100%',14,'ACTIVE','migration:d2','D2 review approved',0),
  ('withdraw.rejected','withdraw','money','server','D2/D4/L',1,'100%',14,'ACTIVE','migration:d2','D2 review rejected',0),
  ('withdraw.delayed','withdraw','money','server','D2/K/L',1,'100%',14,'ACTIVE','migration:d2','D2 review delayed',0),
  ('withdraw.frozen','withdraw','money','server','D2/B5/K/L',1,'100%',14,'ACTIVE','migration:d2','D2 funds frozen',0),
  ('withdraw.unfrozen','withdraw','money','server','D2/K/L',1,'100%',14,'ACTIVE','migration:d2','D2 funds unfrozen',0),
  ('withdraw.refunded','withdraw','money','server','D2/D4/L',1,'100%',14,'ACTIVE','migration:d2','D2 funds refunded',0),
  ('withdraw.review_due','withdraw','money','server','D2/K/L',1,'100%',14,'ACTIVE','migration:d2','D2 scheduled review due',0)
ON DUPLICATE KEY UPDATE family_key='money', current_revision=14, status='ACTIVE', is_deleted=0, updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, created_at, updated_at, is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,NOW(),NOW(),0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'withdrawal_id' property_name,'id' property_type,1 required_field UNION ALL
  SELECT 'amount','number',1 UNION ALL
  SELECT 'currency','string',1 UNION ALL
  SELECT 'state','string',1 UNION ALL
  SELECT 'reason','string',1 UNION ALL
  SELECT 'address_hash','string',1 UNION ALL
  SELECT 'risk_score','number',1
) p
WHERE s.event_name IN ('withdraw.approved','withdraw.rejected','withdraw.delayed','withdraw.frozen','withdraw.unfrozen','withdraw.refunded','withdraw.review_due')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type), required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0, updated_at=NOW();

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('admin.withdraw_limit_changed','admin','money','server','D2/D5/L3',1,'100%',1,'ACTIVE','migration:d2','D5 non-Phase withdrawal parameter changed',0)
ON DUPLICATE KEY UPDATE family_key='money', current_revision=1, status='ACTIVE', is_deleted=0, updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, created_at, updated_at, is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,NOW(),NOW(),0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'field' property_name,'string' property_type UNION ALL
  SELECT 'before','number' UNION ALL
  SELECT 'after','number' UNION ALL
  SELECT 'operator','string' UNION ALL
  SELECT 'reason','string'
) p
WHERE s.event_name='admin.withdraw_limit_changed'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type), required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0, updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, created_at, updated_at, is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,NOW(),NOW(),0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'hold_days' property_name,'number' property_type,0 required_field UNION ALL
  SELECT 'lifecycle_owner','string',1 UNION ALL
  SELECT 'review_at','timestamp',1 UNION ALL
  SELECT 'freeze_period','string',0
) p
WHERE s.event_name IN ('withdraw.delayed','withdraw.frozen','withdraw.review_due')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type), required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0, updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, created_at, updated_at, is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,NOW(),NOW(),0
FROM nx_event_schema_registry s
JOIN (
  -- Canonical required property tuple: 'operator','string',1
  SELECT 'operator' property_name,'string' property_type,1 required_field
) p
WHERE s.event_name IN ('withdraw.approved','withdraw.rejected','withdraw.delayed','withdraw.frozen','withdraw.unfrozen','withdraw.refunded','withdraw.review_due')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type), required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0, updated_at=NOW();
