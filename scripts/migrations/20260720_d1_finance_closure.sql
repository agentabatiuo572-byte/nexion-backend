-- D1 充值与对账闭环：独立支付商流水、真实费率缓冲账、拒付原子追回、BIN/IP/设备锁。

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='cumulative_deposit_usdt')=0,
  'ALTER TABLE nx_user_wallet ADD COLUMN cumulative_deposit_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER lifetime_earned', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_user_wallet_cumulative_deposit_non_negative')=0,
  'ALTER TABLE nx_user_wallet ADD CONSTRAINT chk_user_wallet_cumulative_deposit_non_negative CHECK (cumulative_deposit_usdt >= 0)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('finance_d1_channel_manage','充值渠道与费率配置','API','/finance/recon','HIGH',1,1,0),
  ('finance_d1_config_manage','银行卡风控参数配置','API','/finance/recon','HIGH',1,1,0),
  ('finance_d1_psp_switch','主备支付商切换','API','/finance/recon','HIGH',1,1,0),
  ('finance_d1_reconcile','充值差异核销','API','/finance/recon','HIGH',1,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), status=1, is_deleted=0, updated_at=NOW();

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='finance_d1_write';
UPDATE nx_admin_permission
   SET status=0, is_deleted=1, updated_at=NOW()
 WHERE permission_code='finance_d1_write';

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code LIKE 'finance_d1_%';
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND p.permission_code LIKE 'finance_d1_%' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','AUDITOR') AND p.permission_code='finance_d1_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.status=1 AND p.is_deleted=0
  AND p.permission_code IN ('finance_d1_channel_manage','finance_d1_config_manage','finance_d1_reconcile',
                            'finance_d1_chargeback_refund','finance_d1_bin_manual_lock','finance_d1_bin_lock','finance_d1_bin_unlock');
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='RISK'
  AND p.status=1 AND p.is_deleted=0
  AND p.permission_code IN ('finance_d1_bin_manual_lock','finance_d1_bin_lock','finance_d1_bin_unlock');

-- Convert legacy display-formatted D1 values (for example "$10", "3.5%" and
-- "1 USDT fixed") into the canonical numeric + unit contract consumed by both
-- the payment ingress and the admin compare-and-set writes.
UPDATE nx_config_item
   SET config_value=REGEXP_SUBSTR(REPLACE(config_value,',',''),'[0-9]+([.][0-9]+)?'),
       value_type='NUMBER', updated_at=NOW()
 WHERE config_key IN (
       'finance.topup.channel.trc20.fee','finance.topup.channel.trc20.min_amount',
       'finance.topup.channel.erc20.fee','finance.topup.channel.erc20.min_amount',
       'finance.topup.channel.btc.fee','finance.topup.channel.btc.min_amount',
       'finance.topup.channel.eth.fee','finance.topup.channel.eth.min_amount',
       'finance.topup.channel.card.fee','finance.topup.channel.card.min_amount',
       'finance.topup.card.threeDsThreshold','finance.topup.card.cardRetryLimit',
       'finance.topup.card.cardLockHours')
   AND is_deleted=0
   AND REGEXP_LIKE(REPLACE(config_value,',',''),'[0-9]+([.][0-9]+)?');

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('finance.topup.channel.trc20.fee_unit','USDT_FIXED','STRING','finance.topup','ADMIN','D1 canonical fee unit',1,0),
  ('finance.topup.channel.erc20.fee_unit','USDT_FIXED','STRING','finance.topup','ADMIN','D1 canonical fee unit',1,0),
  ('finance.topup.channel.btc.fee_unit','PERCENT','STRING','finance.topup','ADMIN','D1 canonical fee unit',1,0),
  ('finance.topup.channel.eth.fee_unit','PERCENT','STRING','finance.topup','ADMIN','D1 canonical fee unit',1,0),
  ('finance.topup.channel.card.fee_unit','PERCENT','STRING','finance.topup','ADMIN','D1 canonical fee unit',1,0),
  ('finance.topup.channel.trc20.min_amount_unit','USD','STRING','finance.topup','ADMIN','D1 canonical minimum unit',1,0),
  ('finance.topup.channel.erc20.min_amount_unit','USD','STRING','finance.topup','ADMIN','D1 canonical minimum unit',1,0),
  ('finance.topup.channel.btc.min_amount_unit','USD','STRING','finance.topup','ADMIN','D1 canonical minimum unit',1,0),
  ('finance.topup.channel.eth.min_amount_unit','USD','STRING','finance.topup','ADMIN','D1 canonical minimum unit',1,0),
  ('finance.topup.channel.card.min_amount_unit','USD','STRING','finance.topup','ADMIN','D1 canonical minimum unit',1,0),
  ('finance.topup.card.threeDsThreshold.unit','USD','STRING','finance.topup','ADMIN','D1 canonical risk unit',1,0),
  ('finance.topup.card.cardRetryLimit.unit','COUNT','STRING','finance.topup','ADMIN','D1 canonical risk unit',1,0),
  ('finance.topup.card.cardLockHours.unit','HOUR','STRING','finance.topup','ADMIN','D1 canonical risk unit',1,0)
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value), value_type=VALUES(value_type),
  config_group=VALUES(config_group), visibility=VALUES(visibility), remark=VALUES(remark),
  status=1, is_deleted=0, updated_at=NOW();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND COLUMN_NAME='card_bin')=0,
  'ALTER TABLE nx_payment_record ADD COLUMN card_bin VARCHAR(16) NULL AFTER signature_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND COLUMN_NAME='client_ip')=0,
  'ALTER TABLE nx_payment_record ADD COLUMN client_ip VARCHAR(64) NULL AFTER card_bin', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND COLUMN_NAME='device_fingerprint')=0,
  'ALTER TABLE nx_payment_record ADD COLUMN device_fingerprint VARCHAR(128) NULL AFTER client_ip', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND COLUMN_NAME='fee_amount_usdt')=0,
  'ALTER TABLE nx_payment_record ADD COLUMN fee_amount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER device_fingerprint', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND COLUMN_NAME='fee_rate_pct')=0,
  'ALTER TABLE nx_payment_record ADD COLUMN fee_rate_pct DECIMAL(9,6) NOT NULL DEFAULT 0 AFTER fee_amount_usdt', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND COLUMN_NAME='wallet_ledger_id')=0,
  'ALTER TABLE nx_payment_record ADD COLUMN wallet_ledger_id BIGINT NULL AFTER fee_rate_pct', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND INDEX_NAME='idx_payment_wallet_ledger')=0,
  'ALTER TABLE nx_payment_record ADD INDEX idx_payment_wallet_ledger (wallet_ledger_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_deposit_reconciliation_writeoff' AND COLUMN_NAME='method')=0,
  'ALTER TABLE nx_deposit_reconciliation_writeoff ADD COLUMN method VARCHAR(32) NOT NULL DEFAULT ''CONFIRM_EXCEPTION'' AFTER reconcile_date', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_deposit_reconciliation_writeoff' AND COLUMN_NAME='evidence_ref')=0,
  'ALTER TABLE nx_deposit_reconciliation_writeoff ADD COLUMN evidence_ref VARCHAR(128) NOT NULL DEFAULT ''LEGACY'' AFTER method', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_topup_provider_statement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ingestion_event_id VARCHAR(128) NULL,
  payload_hash CHAR(64) NULL,
  statement_no VARCHAR(96) NOT NULL,
  provider VARCHAR(32) NOT NULL,
  channel_code VARCHAR(64) NOT NULL,
  provider_reference VARCHAR(128) NOT NULL,
  user_id BIGINT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  statement_status VARCHAR(32) NOT NULL,
  evidence_ref VARCHAR(128) NULL,
  observed_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_provider_statement_no (statement_no),
  UNIQUE KEY uk_topup_provider_reference (provider, provider_reference),
  KEY idx_topup_provider_channel_time (channel_code, observed_at),
  CONSTRAINT chk_topup_provider_amount CHECK (amount_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_provider_statement' AND COLUMN_NAME='ingestion_event_id')=0,
  'ALTER TABLE nx_topup_provider_statement ADD COLUMN ingestion_event_id VARCHAR(128) NULL AFTER id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_provider_statement' AND COLUMN_NAME='payload_hash')=0,
  'ALTER TABLE nx_topup_provider_statement ADD COLUMN payload_hash CHAR(64) NULL AFTER ingestion_event_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_provider_statement' AND INDEX_NAME='uk_topup_provider_ingestion_event')=0,
  'ALTER TABLE nx_topup_provider_statement ADD UNIQUE KEY uk_topup_provider_ingestion_event (ingestion_event_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_topup_card_admission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admission_event_id VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  card_bin VARCHAR(16) NOT NULL,
  client_ip VARCHAR(64) NOT NULL,
  device_fingerprint VARCHAR(128) NOT NULL,
  provider VARCHAR(32) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  fee_amount_usdt DECIMAL(18,6) NOT NULL,
  fee_rate_pct DECIMAL(9,6) NOT NULL,
  three_ds_status VARCHAR(16) NOT NULL,
  decision VARCHAR(16) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  expires_at DATETIME NOT NULL,
  settlement_event_id VARCHAR(128) NULL,
  failure_event_id VARCHAR(128) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_card_admission_event (admission_event_id),
  UNIQUE KEY uk_topup_card_admission_order (order_no),
  UNIQUE KEY uk_topup_card_admission_settlement (settlement_event_id),
  UNIQUE KEY uk_topup_card_admission_failure (failure_event_id),
  KEY idx_topup_card_admission_order (order_no, user_id, decision, expires_at),
  CONSTRAINT chk_topup_card_admission_decision CHECK (decision IN ('ALLOWED','DENIED')),
  CONSTRAINT chk_topup_card_admission_amounts CHECK (
    amount_usdt > 0 AND fee_amount_usdt >= 0 AND fee_rate_pct >= 0),
  CONSTRAINT chk_topup_card_admission_3ds CHECK (three_ds_status IN ('AUTHENTICATED','EXEMPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_admission' AND COLUMN_NAME='provider')=0,
  'ALTER TABLE nx_topup_card_admission ADD COLUMN provider VARCHAR(32) NULL AFTER device_fingerprint', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_admission' AND COLUMN_NAME='amount_usdt')=0,
  'ALTER TABLE nx_topup_card_admission ADD COLUMN amount_usdt DECIMAL(18,6) NULL AFTER provider', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_admission' AND COLUMN_NAME='fee_amount_usdt')=0,
  'ALTER TABLE nx_topup_card_admission ADD COLUMN fee_amount_usdt DECIMAL(18,6) NULL AFTER amount_usdt', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_admission' AND COLUMN_NAME='fee_rate_pct')=0,
  'ALTER TABLE nx_topup_card_admission ADD COLUMN fee_rate_pct DECIMAL(9,6) NULL AFTER fee_amount_usdt', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_admission' AND COLUMN_NAME='three_ds_status')=0,
  'ALTER TABLE nx_topup_card_admission ADD COLUMN three_ds_status VARCHAR(16) NULL AFTER fee_rate_pct', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_admission' AND COLUMN_NAME='failure_event_id')=0,
  'ALTER TABLE nx_topup_card_admission ADD COLUMN failure_event_id VARCHAR(128) NULL AFTER settlement_event_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_admission' AND INDEX_NAME='uk_topup_card_admission_failure')=0,
  'ALTER TABLE nx_topup_card_admission ADD UNIQUE KEY uk_topup_card_admission_failure (failure_event_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
CREATE TABLE IF NOT EXISTS nx_topup_card_admission_legacy_archive (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admission_event_id VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  card_bin VARCHAR(16) NOT NULL,
  client_ip VARCHAR(64) NOT NULL,
  device_fingerprint VARCHAR(128) NOT NULL,
  decision VARCHAR(16) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  expires_at DATETIME NOT NULL,
  settlement_event_id VARCHAR(128) NULL,
  original_created_at DATETIME NULL,
  original_updated_at DATETIME NULL,
  archive_reason VARCHAR(96) NOT NULL,
  archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_topup_card_admission_legacy_event (admission_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @d1_legacy_admission_count = (SELECT COUNT(*) FROM nx_topup_card_admission
 WHERE provider IS NULL OR amount_usdt IS NULL OR fee_amount_usdt IS NULL
    OR fee_rate_pct IS NULL OR three_ds_status IS NULL);
INSERT IGNORE INTO nx_topup_card_admission_legacy_archive
  (admission_event_id, request_hash, order_no, user_id, card_bin, client_ip, device_fingerprint,
   decision, reason, expires_at, settlement_event_id, original_created_at, original_updated_at,
   archive_reason, archived_at)
SELECT admission_event_id, request_hash, order_no, user_id, card_bin, client_ip, device_fingerprint,
       decision, reason, expires_at, settlement_event_id, created_at, updated_at,
       'PRE_AUTHORIZATION_SNAPSHOT_SCHEMA', NOW()
  FROM nx_topup_card_admission
 WHERE provider IS NULL OR amount_usdt IS NULL OR fee_amount_usdt IS NULL
    OR fee_rate_pct IS NULL OR three_ds_status IS NULL;
DELETE FROM nx_topup_card_admission
 WHERE admission_event_id IN (SELECT admission_event_id FROM nx_topup_card_admission_legacy_archive);
INSERT INTO nx_audit_log
  (service_name, action, resource_type, resource_id, actor_type, actor_username,
   result, risk_level, detail_json, created_at, is_deleted)
SELECT 'nexion-backend', 'D1_LEGACY_ADMISSION_ARCHIVED', 'SCHEMA_MIGRATION',
       '20260720_d1_admission_v2', 'SYSTEM', 'schema-migration', 'SUCCESS', 'MEDIUM',
       JSON_OBJECT('archivedCount', @d1_legacy_admission_count,
                   'archiveTable', 'nx_topup_card_admission_legacy_archive'), NOW(), 0
 WHERE @d1_legacy_admission_count > 0;
ALTER TABLE nx_topup_card_admission
  MODIFY COLUMN provider VARCHAR(32) NOT NULL,
  MODIFY COLUMN amount_usdt DECIMAL(18,6) NOT NULL,
  MODIFY COLUMN fee_amount_usdt DECIMAL(18,6) NOT NULL,
  MODIFY COLUMN fee_rate_pct DECIMAL(9,6) NOT NULL,
  MODIFY COLUMN three_ds_status VARCHAR(16) NOT NULL;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_admission' AND INDEX_NAME='uk_topup_card_admission_order')=0,
  'ALTER TABLE nx_topup_card_admission ADD UNIQUE KEY uk_topup_card_admission_order (order_no)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_topup_card_admission_amounts')=0,
  'ALTER TABLE nx_topup_card_admission ADD CONSTRAINT chk_topup_card_admission_amounts CHECK (amount_usdt > 0 AND fee_amount_usdt >= 0 AND fee_rate_pct >= 0)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_topup_card_admission_3ds')=0,
  'ALTER TABLE nx_topup_card_admission ADD CONSTRAINT chk_topup_card_admission_3ds CHECK (three_ds_status IN (''AUTHENTICATED'',''EXEMPTED''))', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_topup_card_settlement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  settlement_event_id VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  admission_event_id VARCHAR(128) NOT NULL,
  payment_no VARCHAR(96) NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  provider VARCHAR(32) NOT NULL,
  provider_payment_id VARCHAR(128) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  fee_amount_usdt DECIMAL(18,6) NOT NULL,
  fee_rate_pct DECIMAL(9,6) NOT NULL,
  status VARCHAR(16) NOT NULL,
  wallet_balance_after DECIMAL(18,6) NULL,
  cumulative_deposit_after DECIMAL(18,6) NULL,
  fee_buffer_balance_after DECIMAL(18,6) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_card_settlement_event (settlement_event_id),
  UNIQUE KEY uk_topup_card_settlement_payment (payment_no),
  UNIQUE KEY uk_topup_card_settlement_order (order_no),
  UNIQUE KEY uk_topup_card_settlement_provider (provider, provider_payment_id),
  KEY idx_topup_card_settlement_order (order_no, user_id, status),
  CONSTRAINT chk_topup_card_settlement_amounts CHECK (
    amount_usdt > 0 AND fee_amount_usdt >= 0 AND fee_rate_pct >= 0),
  CONSTRAINT chk_topup_card_settlement_status CHECK (status IN ('PROCESSING','SETTLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_topup_card_failure_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  failure_event_id VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  admission_event_id VARCHAR(128) NOT NULL,
  payment_no VARCHAR(96) NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  provider VARCHAR(32) NOT NULL,
  provider_payment_id VARCHAR(128) NOT NULL,
  failure_status VARCHAR(16) NOT NULL,
  failure_reason VARCHAR(255) NOT NULL,
  occurred_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_card_failure_event (failure_event_id),
  UNIQUE KEY uk_topup_card_failure_payment (payment_no),
  UNIQUE KEY uk_topup_card_failure_order (order_no),
  UNIQUE KEY uk_topup_card_failure_provider (provider, provider_payment_id),
  KEY idx_topup_card_failure_risk (failure_status, occurred_at),
  CONSTRAINT chk_topup_card_failure_status CHECK (failure_status IN ('FAILED','DECLINED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_topup_card_chargeback_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  chargeback_event_id VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  payment_no VARCHAR(96) NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  provider VARCHAR(32) NOT NULL,
  provider_payment_id VARCHAR(128) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  chargeback_status VARCHAR(32) NOT NULL,
  chargeback_reason VARCHAR(255) NOT NULL,
  evidence_ref VARCHAR(128) NOT NULL,
  occurred_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_chargeback_event (chargeback_event_id),
  KEY idx_topup_chargeback_payment (payment_no, occurred_at),
  KEY idx_topup_chargeback_provider (provider, provider_payment_id, occurred_at),
  CONSTRAINT chk_topup_chargeback_event_amount CHECK (amount_usdt > 0),
  CONSTRAINT chk_topup_chargeback_event_status CHECK (
    chargeback_status IN ('CHARGEBACK','DISPUTED','CHARGEBACK_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_card_settlement' AND INDEX_NAME='uk_topup_card_settlement_order')=0,
  'ALTER TABLE nx_topup_card_settlement ADD UNIQUE KEY uk_topup_card_settlement_order (order_no)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_topup_fee_buffer_account (
  id TINYINT PRIMARY KEY,
  balance_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_topup_fee_buffer_balance CHECK (balance_usdt >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO nx_topup_fee_buffer_account(id, balance_usdt, version)
VALUES (1, 0, 0) ON DUPLICATE KEY UPDATE id=VALUES(id);

CREATE TABLE IF NOT EXISTS nx_topup_fee_buffer_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  entry_no VARCHAR(96) NOT NULL,
  biz_no VARCHAR(96) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  balance_after_usdt DECIMAL(18,6) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  operator VARCHAR(96) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_fee_buffer_entry (entry_no),
  UNIQUE KEY uk_topup_fee_buffer_idem (idempotency_key),
  KEY idx_topup_fee_buffer_biz (biz_no, created_at),
  CONSTRAINT chk_topup_fee_buffer_amount CHECK (amount_usdt > 0 AND balance_after_usdt >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_topup_chargeback_recovery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recovery_no VARCHAR(96) NOT NULL,
  case_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  recovered_usdt DECIMAL(18,6) NOT NULL,
  wallet_shortfall_usdt DECIMAL(18,6) NOT NULL,
  fee_buffer_required_usdt DECIMAL(18,6) NOT NULL,
  fee_buffer_deducted_usdt DECIMAL(18,6) NOT NULL,
  fee_buffer_shortfall_usdt DECIMAL(18,6) NOT NULL,
  cumulative_before_usdt DECIMAL(18,6) NOT NULL,
  cumulative_after_usdt DECIMAL(18,6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  evidence_ref VARCHAR(128) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  operator VARCHAR(96) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  ledger_biz_no VARCHAR(96) NULL,
  risk_signal_no VARCHAR(96) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_chargeback_recovery_no (recovery_no),
  UNIQUE KEY uk_topup_chargeback_case (case_no),
  UNIQUE KEY uk_topup_chargeback_idem (idempotency_key),
  KEY idx_topup_chargeback_user_time (user_id, created_at),
  CONSTRAINT chk_topup_chargeback_non_negative CHECK (
    amount_usdt > 0 AND recovered_usdt >= 0 AND wallet_shortfall_usdt >= 0
    AND fee_buffer_required_usdt >= 0 AND fee_buffer_deducted_usdt >= 0
    AND fee_buffer_shortfall_usdt >= 0 AND cumulative_before_usdt >= 0 AND cumulative_after_usdt >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_topup_chargeback_recovery' AND COLUMN_NAME='ledger_biz_no')='NO',
  'ALTER TABLE nx_topup_chargeback_recovery MODIFY COLUMN ledger_biz_no VARCHAR(96) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_topup_risk_lock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  target_type VARCHAR(16) NOT NULL,
  target_value VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  source VARCHAR(16) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  locked_until DATETIME NOT NULL,
  created_by VARCHAR(96) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_risk_target (target_type, target_value),
  KEY idx_topup_risk_active (status, locked_until),
  CONSTRAINT chk_topup_risk_target_type CHECK (target_type IN ('BIN','IP','DEVICE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND INDEX_NAME='idx_payment_risk_bin_time')=0,
  'ALTER TABLE nx_payment_record ADD INDEX idx_payment_risk_bin_time (card_bin, payment_status, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND INDEX_NAME='idx_payment_risk_ip_time')=0,
  'ALTER TABLE nx_payment_record ADD INDEX idx_payment_risk_ip_time (client_ip, payment_status, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_record' AND INDEX_NAME='idx_payment_risk_device_time')=0,
  'ALTER TABLE nx_payment_record ADD INDEX idx_payment_risk_device_time (device_fingerprint, payment_status, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- A4 money schemas: admission is initiated; settlement uses the existing confirmed event.
INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('wallet.topup_initiated', 'wallet', 'monetization', 'server', 'D1/L1/L2 BI', 1,
   '100%', 12, 'ACTIVE', 'migration:d1', 'Card admission accepted before PSP settlement', 0)
ON DUPLICATE KEY UPDATE status='ACTIVE', is_deleted=0, updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, created_at, updated_at, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, p.required_field, s.current_revision, NOW(), NOW(), 0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'amount' property_name, 'number' property_type, 1 required_field UNION ALL
  SELECT 'currency', 'string', 1 UNION ALL
  SELECT 'channel', 'string', 1 UNION ALL
  SELECT 'topup_id', 'id', 1 UNION ALL
  SELECT 'psp', 'string', 1
) p
WHERE s.event_name='wallet.topup_confirmed' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type), required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0, updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, created_at, updated_at, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, p.required_field, s.current_revision, NOW(), NOW(), 0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'transaction_id' property_name, 'id' property_type, 1 required_field UNION ALL
  SELECT 'amount', 'number', 1 UNION ALL
  SELECT 'currency', 'string', 1 UNION ALL
  SELECT 'channel', 'string', 1 UNION ALL
  SELECT 'topup_id', 'id', 1 UNION ALL
  SELECT 'psp', 'string', 1 UNION ALL
  SELECT 'three_ds_status', 'enum', 1
) p
WHERE s.event_name='wallet.topup_initiated' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type), required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0, updated_at=NOW();

-- Historical cumulative-deposit backfill. Only exact, ledger-bound USDT credits are accepted;
-- ambiguous legacy rows are quarantined for manual reconciliation instead of being guessed.
CREATE TABLE IF NOT EXISTS nx_topup_cumulative_backfill_anomaly (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type VARCHAR(32) NOT NULL,
  source_no VARCHAR(128) NOT NULL,
  user_id BIGINT NULL,
  amount_usdt DECIMAL(18,6) NULL,
  reason_code VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_topup_backfill_anomaly (source_type, source_no, reason_code),
  KEY idx_topup_backfill_anomaly_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_topup_cumulative_backfill_run (
  migration_key VARCHAR(64) PRIMARY KEY,
  verified_credit_count BIGINT NOT NULL,
  anomaly_count BIGINT NOT NULL,
  executed_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Establish exact D4 bindings before any fee/D3/cumulative reconstruction. Duplicate matches stay quarantined.
UPDATE nx_payment_record p
JOIN (
  SELECT p0.id AS payment_id, MIN(l0.id) AS ledger_id
    FROM nx_payment_record p0
    JOIN nx_wallet_ledger l0
      ON l0.user_id=p0.user_id AND l0.biz_no=p0.payment_no AND l0.biz_type='CARD_TOPUP'
     AND l0.asset='USDT' AND l0.direction='IN' AND l0.status='SUCCESS'
     AND l0.amount=p0.amount_usdt AND l0.is_deleted=0
   WHERE p0.is_deleted=0
   GROUP BY p0.id HAVING COUNT(*)=1
) exact_binding ON exact_binding.payment_id=p.id
SET p.wallet_ledger_id=exact_binding.ledger_id, p.updated_at=NOW()
WHERE p.is_deleted=0 AND p.wallet_ledger_id IS NULL;

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt, 'DUPLICATE_D4_BINDING', 'OPEN', NOW(), NOW(), 0
  FROM nx_payment_record p
  JOIN nx_wallet_ledger l
    ON l.user_id=p.user_id AND l.biz_no=p.payment_no AND l.biz_type='CARD_TOPUP'
   AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS'
   AND l.amount=p.amount_usdt AND l.is_deleted=0
 WHERE p.is_deleted=0
 GROUP BY p.id,p.payment_no,p.user_id,p.amount_usdt HAVING COUNT(*)>1
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt, 'INVALID_D4_BINDING', 'OPEN', NOW(), NOW(), 0
  FROM nx_payment_record p
  LEFT JOIN nx_wallet_ledger l
    ON l.id=p.wallet_ledger_id AND l.is_deleted=0 AND l.user_id=p.user_id
   AND l.biz_no=p.payment_no AND l.biz_type='CARD_TOPUP' AND l.asset='USDT'
   AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=p.amount_usdt
 WHERE p.is_deleted=0 AND p.wallet_ledger_id IS NOT NULL AND l.id IS NULL
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

-- Legacy CHARGEBACK_REFUNDED only changed a status flag; it did not atomically
-- reverse wallet, cumulative deposit, fee buffer or D3 reserve. Keep readiness
-- failed until an operator completes the evidence-backed recovery path.
INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt,
       'LEGACY_STATUS_ONLY_CHARGEBACK', 'OPEN', NOW(), NOW(), 0
  FROM nx_payment_record p
 WHERE p.is_deleted=0 AND p.payment_status='CHARGEBACK_REFUNDED'
   AND NOT EXISTS (
     SELECT 1 FROM nx_topup_chargeback_recovery r
      WHERE r.is_deleted=0 AND r.case_no=p.payment_no
        AND r.status IN ('RECOVERED','PARTIAL_ANOMALY'))
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

UPDATE nx_topup_cumulative_backfill_anomaly a
JOIN nx_topup_chargeback_recovery r
  ON a.source_type='PAYMENT' AND a.source_no=r.case_no
 AND r.is_deleted=0 AND r.status IN ('RECOVERED','PARTIAL_ANOMALY')
SET a.status='RESOLVED', a.updated_at=NOW()
WHERE a.reason_code='LEGACY_STATUS_ONLY_CHARGEBACK' AND a.is_deleted=0;

-- Recover historical card fee evidence only from an exact, settled gateway receipt. Never infer a rate.
UPDATE nx_payment_record p
JOIN nx_topup_card_settlement s
  ON s.payment_no=p.payment_no AND s.order_no=p.order_no AND s.user_id=p.user_id
 AND s.provider=p.provider AND s.provider_payment_id=p.provider_payment_id
 AND s.amount_usdt=p.amount_usdt AND s.status='SETTLED' AND s.is_deleted=0
SET p.fee_amount_usdt=s.fee_amount_usdt, p.fee_rate_pct=s.fee_rate_pct, p.updated_at=NOW()
WHERE p.is_deleted=0
  AND (p.fee_amount_usdt<>s.fee_amount_usdt OR p.fee_rate_pct<>s.fee_rate_pct);

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt, 'FEE_EVIDENCE_MISSING', 'OPEN', NOW(), NOW(), 0
  FROM nx_payment_record p
  JOIN nx_wallet_ledger l ON l.id=p.wallet_ledger_id AND l.is_deleted=0
 WHERE p.is_deleted=0 AND UPPER(p.provider) IN ('CHECKOUT.COM','STRIPE','CARD')
   AND p.payment_status IN ('CONFIRMED','PAID','SUCCESS','CHARGEBACK','DISPUTED','CHARGEBACK_REVIEW',
                            'CHARGEBACK_REFUNDED','CHARGEBACK_RECOVERED','CHARGEBACK_PARTIAL')
   AND l.user_id=p.user_id AND l.biz_no=p.payment_no AND l.biz_type='CARD_TOPUP'
   AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=p.amount_usdt
   AND NOT EXISTS (
     SELECT 1 FROM nx_topup_card_settlement s
      WHERE s.is_deleted=0 AND s.status='SETTLED' AND s.payment_no=p.payment_no
        AND s.order_no=p.order_no AND s.user_id=p.user_id AND s.provider=p.provider
        AND s.provider_payment_id=p.provider_payment_id AND s.amount_usdt=p.amount_usdt
        AND s.fee_amount_usdt=p.fee_amount_usdt AND s.fee_rate_pct=p.fee_rate_pct)
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

UPDATE nx_topup_cumulative_backfill_anomaly a
JOIN nx_payment_record p ON a.source_type='PAYMENT' AND a.source_no=p.payment_no
JOIN nx_topup_card_settlement s
  ON s.payment_no=p.payment_no AND s.order_no=p.order_no AND s.user_id=p.user_id
 AND s.provider=p.provider AND s.provider_payment_id=p.provider_payment_id
 AND s.amount_usdt=p.amount_usdt AND s.fee_amount_usdt=p.fee_amount_usdt
 AND s.fee_rate_pct=p.fee_rate_pct AND s.status='SETTLED' AND s.is_deleted=0
SET a.status='RESOLVED', a.updated_at=NOW()
WHERE a.reason_code='FEE_EVIDENCE_MISSING' AND a.is_deleted=0;

INSERT IGNORE INTO nx_topup_fee_buffer_ledger
  (entry_no, biz_no, direction, amount_usdt, balance_after_usdt, reason, operator,
   idempotency_key, created_at, updated_at, is_deleted)
SELECT CONCAT('FEE-MIG-', LEFT(SHA2(p.payment_no,256), 56)), p.payment_no, 'IN', p.fee_amount_usdt,
       p.fee_amount_usdt, 'D1 exact historical card fee backfill', 'schema-migration',
       CONCAT('D1-FEE-IN-', SHA2(p.payment_no,256)), COALESCE(p.paid_at,p.created_at), NOW(), 0
  FROM nx_payment_record p
  JOIN nx_topup_card_settlement s
    ON s.payment_no=p.payment_no AND s.order_no=p.order_no AND s.user_id=p.user_id
   AND s.provider=p.provider AND s.provider_payment_id=p.provider_payment_id
   AND s.amount_usdt=p.amount_usdt AND s.fee_amount_usdt=p.fee_amount_usdt
   AND s.fee_rate_pct=p.fee_rate_pct AND s.status='SETTLED' AND s.is_deleted=0
 WHERE p.is_deleted=0 AND p.fee_amount_usdt>0
   AND NOT EXISTS (SELECT 1 FROM nx_topup_fee_buffer_ledger f
                    WHERE f.is_deleted=0 AND f.biz_no=p.payment_no AND f.direction='IN');

INSERT IGNORE INTO nx_topup_fee_buffer_ledger
  (entry_no, biz_no, direction, amount_usdt, balance_after_usdt, reason, operator,
   idempotency_key, created_at, updated_at, is_deleted)
SELECT CONCAT('FEE-CB-MIG-', LEFT(SHA2(p.payment_no,256), 53)),
       COALESCE(r.ledger_biz_no,p.payment_no), 'OUT', r.fee_buffer_deducted_usdt,
       0, 'D1 exact historical chargeback fee reversal', 'schema-migration',
       CONCAT('D1-FEE-CB-', SHA2(p.payment_no,256)), r.created_at, NOW(), 0
  FROM nx_topup_chargeback_recovery r
  JOIN nx_payment_record p ON p.payment_no=r.case_no AND p.user_id=r.user_id AND p.is_deleted=0
  JOIN nx_topup_card_settlement s
    ON s.payment_no=p.payment_no AND s.order_no=p.order_no AND s.user_id=p.user_id
   AND s.provider=p.provider AND s.provider_payment_id=p.provider_payment_id
   AND s.amount_usdt=p.amount_usdt AND s.fee_amount_usdt=p.fee_amount_usdt
   AND s.fee_rate_pct=p.fee_rate_pct AND s.status='SETTLED' AND s.is_deleted=0
 WHERE r.is_deleted=0 AND r.status IN ('RECOVERED','PARTIAL_ANOMALY')
   AND r.fee_buffer_deducted_usdt>0
   AND NOT EXISTS (SELECT 1 FROM nx_topup_fee_buffer_ledger f
                    WHERE f.is_deleted=0 AND f.direction='OUT'
                      AND (f.idempotency_key=r.idempotency_key
                           OR (r.ledger_biz_no IS NOT NULL AND f.biz_no=r.ledger_biz_no)
                           OR f.idempotency_key=CONCAT('D1-FEE-CB-', SHA2(p.payment_no,256))));

UPDATE nx_topup_fee_buffer_ledger f
JOIN (
  SELECT id, GREATEST(SUM(CASE WHEN direction='IN' THEN amount_usdt ELSE -amount_usdt END)
                 OVER (ORDER BY created_at,id ROWS UNBOUNDED PRECEDING), 0) AS exact_balance
    FROM nx_topup_fee_buffer_ledger WHERE is_deleted=0
) x ON x.id=f.id
SET f.balance_after_usdt=x.exact_balance, f.updated_at=NOW()
WHERE f.is_deleted=0;
UPDATE nx_topup_fee_buffer_account a
JOIN (SELECT GREATEST(COALESCE(SUM(CASE WHEN direction='IN' THEN amount_usdt ELSE -amount_usdt END),0),0) AS exact_balance
        FROM nx_topup_fee_buffer_ledger WHERE is_deleted=0) x
SET a.balance_usdt=x.exact_balance, a.version=a.version+1, a.updated_at=NOW()
WHERE a.id=1 AND a.balance_usdt<>x.exact_balance;

-- Rebuild D3 reserve only from the same exact D4-bound card credits used by cumulative backfill.
INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt, 'TREASURY_RESERVE_EVIDENCE_MISMATCH',
       'OPEN', NOW(), NOW(), 0
  FROM nx_payment_record p
  JOIN nx_wallet_ledger l ON l.id=p.wallet_ledger_id AND l.is_deleted=0
  JOIN nx_treasury_reserve_ledger t ON t.voucher_no=p.payment_no AND t.is_deleted=0
 WHERE p.is_deleted=0 AND l.user_id=p.user_id AND l.biz_no=p.payment_no
   AND p.payment_status IN ('CONFIRMED','PAID','SUCCESS','CHARGEBACK','DISPUTED','CHARGEBACK_REVIEW',
                            'CHARGEBACK_REFUNDED','CHARGEBACK_RECOVERED','CHARGEBACK_PARTIAL')
   AND l.biz_type='CARD_TOPUP' AND l.asset='USDT' AND l.direction='IN'
   AND l.status='SUCCESS' AND l.amount=p.amount_usdt
   AND NOT (t.direction='IN' AND t.status='CONFIRMED' AND t.amount_usd=p.amount_usdt)
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

INSERT IGNORE INTO nx_treasury_reserve_ledger
  (reserve_no, voucher_no, direction, amount_usd, reason, operator, idempotency_key,
   status, created_at, updated_at, is_deleted)
SELECT CONCAT('RSV-D1-', LEFT(SHA2(p.payment_no,256),57)), p.payment_no, 'IN', p.amount_usdt,
       'D1 exact historical card topup reserve', 'schema-migration',
       CONCAT('D1-RES-IN-', SHA2(p.payment_no,256)), 'CONFIRMED', COALESCE(p.paid_at,p.created_at), NOW(), 0
  FROM nx_payment_record p
  JOIN nx_wallet_ledger l ON l.id=p.wallet_ledger_id AND l.is_deleted=0
 WHERE p.is_deleted=0 AND l.user_id=p.user_id AND l.biz_no=p.payment_no
   AND p.payment_status IN ('CONFIRMED','PAID','SUCCESS','CHARGEBACK','DISPUTED','CHARGEBACK_REVIEW',
                            'CHARGEBACK_REFUNDED','CHARGEBACK_RECOVERED','CHARGEBACK_PARTIAL')
   AND l.biz_type='CARD_TOPUP' AND l.asset='USDT' AND l.direction='IN'
   AND l.status='SUCCESS' AND l.amount=p.amount_usdt
   AND NOT EXISTS (SELECT 1 FROM nx_treasury_reserve_ledger t
                    WHERE t.is_deleted=0 AND t.voucher_no=p.payment_no);

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt, 'TREASURY_RESERVE_EVIDENCE_MISSING',
       'OPEN', NOW(), NOW(), 0
  FROM nx_payment_record p
  JOIN nx_wallet_ledger l ON l.id=p.wallet_ledger_id AND l.is_deleted=0
 WHERE p.is_deleted=0 AND l.user_id=p.user_id AND l.biz_no=p.payment_no
   AND p.payment_status IN ('CONFIRMED','PAID','SUCCESS','CHARGEBACK','DISPUTED','CHARGEBACK_REVIEW',
                            'CHARGEBACK_REFUNDED','CHARGEBACK_RECOVERED','CHARGEBACK_PARTIAL')
   AND l.biz_type='CARD_TOPUP' AND l.asset='USDT' AND l.direction='IN'
   AND l.status='SUCCESS' AND l.amount=p.amount_usdt
   AND NOT EXISTS (SELECT 1 FROM nx_treasury_reserve_ledger t
                    WHERE t.is_deleted=0 AND t.voucher_no=p.payment_no
                      AND t.direction='IN' AND t.status='CONFIRMED' AND t.amount_usd=p.amount_usdt)
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

UPDATE nx_topup_cumulative_backfill_anomaly a
JOIN nx_payment_record p ON a.source_type='PAYMENT' AND a.source_no=p.payment_no
JOIN nx_treasury_reserve_ledger t
  ON t.voucher_no=p.payment_no AND t.direction='IN' AND t.status='CONFIRMED'
 AND t.amount_usd=p.amount_usdt AND t.is_deleted=0
SET a.status='RESOLVED', a.updated_at=NOW()
WHERE a.reason_code IN ('TREASURY_RESERVE_EVIDENCE_MISSING','TREASURY_RESERVE_EVIDENCE_MISMATCH')
  AND a.is_deleted=0
  AND NOT EXISTS (
    SELECT 1 FROM nx_treasury_reserve_ledger bad
     WHERE bad.voucher_no=p.payment_no AND bad.is_deleted=0
       AND NOT (bad.direction='IN' AND bad.status='CONFIRMED' AND bad.amount_usd=p.amount_usdt));

INSERT IGNORE INTO nx_treasury_reserve_ledger
  (reserve_no, voucher_no, direction, amount_usd, reason, operator, idempotency_key,
   status, created_at, updated_at, is_deleted)
SELECT CONCAT('RSV-D1-CB-', LEFT(SHA2(p.payment_no,256),54)),
       CONCAT('CB-', LEFT(SHA2(p.payment_no,256),61)), 'OUT', p.amount_usdt,
       'D1 exact historical chargeback reserve reversal', 'schema-migration',
       CONCAT('D1-RES-CB-', SHA2(p.payment_no,256)), 'CONFIRMED', r.created_at, NOW(), 0
  FROM nx_topup_chargeback_recovery r
  JOIN nx_payment_record p ON p.payment_no=r.case_no AND p.user_id=r.user_id AND p.is_deleted=0
  JOIN nx_treasury_reserve_ledger original
    ON original.voucher_no=p.payment_no AND original.direction='IN' AND original.status='CONFIRMED'
   AND original.amount_usd=p.amount_usdt AND original.is_deleted=0
 WHERE r.is_deleted=0 AND r.status IN ('RECOVERED','PARTIAL_ANOMALY')
   AND NOT EXISTS (SELECT 1 FROM nx_treasury_reserve_ledger t
                    WHERE t.is_deleted=0 AND t.direction='OUT' AND t.status='CONFIRMED'
                      AND t.amount_usd=p.amount_usdt
                      AND (t.idempotency_key=CONCAT('reserve-',r.idempotency_key)
                           OR t.idempotency_key=CONCAT('D1-RES-CB-', SHA2(p.payment_no,256))
                           OR t.voucher_no=CONCAT('CB-',p.payment_no)));

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt,
       'TREASURY_REVERSAL_EVIDENCE_MISMATCH', 'OPEN', NOW(), NOW(), 0
  FROM nx_topup_chargeback_recovery r
  JOIN nx_payment_record p ON p.payment_no=r.case_no AND p.user_id=r.user_id AND p.is_deleted=0
  JOIN nx_treasury_reserve_ledger t
    ON t.is_deleted=0 AND t.direction='OUT' AND t.status='CONFIRMED'
   AND (t.idempotency_key=CONCAT('reserve-',r.idempotency_key)
        OR t.idempotency_key=CONCAT('D1-RES-CB-', SHA2(p.payment_no,256))
        OR t.voucher_no=CONCAT('CB-',p.payment_no))
 WHERE r.is_deleted=0 AND r.status IN ('RECOVERED','PARTIAL_ANOMALY')
   AND t.amount_usd<>p.amount_usdt
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

-- Exact USDT chain credits can be reconstructed 1:1; BTC/ETH require historical valuation evidence.
INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'DEPOSIT', d.deposit_no, d.user_id, d.amount,
       'TREASURY_VALUATION_EVIDENCE_MISSING', 'OPEN', NOW(), NOW(), 0
  FROM nx_deposit_order d
  JOIN nx_wallet_ledger l
    ON l.id=d.ledger_id AND l.is_deleted=0 AND l.user_id=d.user_id
   AND l.biz_no=d.deposit_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
   AND l.asset=d.asset AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=d.amount
 WHERE d.is_deleted=0 AND d.asset IN ('BTC','ETH')
   AND d.status IN ('CONFIRMED','CREDITED','SUCCESS')
   AND l.user_id=d.user_id AND l.asset=d.asset AND l.direction='IN'
   AND l.status='SUCCESS' AND l.amount=d.amount
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'DEPOSIT', d.deposit_no, d.user_id, d.amount,
       'TREASURY_RESERVE_EVIDENCE_MISMATCH', 'OPEN', NOW(), NOW(), 0
  FROM nx_deposit_order d
  JOIN nx_wallet_ledger l
    ON l.id=d.ledger_id AND l.is_deleted=0 AND l.user_id=d.user_id
   AND l.biz_no=d.deposit_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
   AND l.asset=d.asset AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=d.amount
  JOIN nx_treasury_reserve_ledger t ON t.voucher_no=d.deposit_no AND t.is_deleted=0
 WHERE d.is_deleted=0 AND d.asset='USDT' AND d.status IN ('CONFIRMED','CREDITED','SUCCESS')
   AND l.user_id=d.user_id AND l.asset='USDT' AND l.direction='IN'
   AND l.status='SUCCESS' AND l.amount=d.amount
   AND NOT (t.direction='IN' AND t.status='CONFIRMED' AND t.amount_usd=d.amount)
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

INSERT IGNORE INTO nx_treasury_reserve_ledger
  (reserve_no, voucher_no, direction, amount_usd, reason, operator, idempotency_key,
   status, created_at, updated_at, is_deleted)
SELECT CONCAT('RSV-D1-CH-', LEFT(SHA2(d.deposit_no,256),54)), d.deposit_no, 'IN', d.amount,
       'D1 exact historical chain topup reserve', 'schema-migration',
       CONCAT('D1-RES-CH-', SHA2(d.deposit_no,256)), 'CONFIRMED', d.created_at, NOW(), 0
  FROM nx_deposit_order d
  JOIN nx_wallet_ledger l
    ON l.id=d.ledger_id AND l.is_deleted=0 AND l.user_id=d.user_id
   AND l.biz_no=d.deposit_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
   AND l.asset=d.asset AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=d.amount
 WHERE d.is_deleted=0 AND d.asset='USDT' AND d.status IN ('CONFIRMED','CREDITED','SUCCESS')
   AND l.user_id=d.user_id AND l.asset='USDT' AND l.direction='IN'
   AND l.status='SUCCESS' AND l.amount=d.amount
   AND NOT EXISTS (SELECT 1 FROM nx_treasury_reserve_ledger t
                    WHERE t.is_deleted=0 AND t.voucher_no=d.deposit_no);

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'DEPOSIT', d.deposit_no, d.user_id, d.amount,
       'TREASURY_RESERVE_EVIDENCE_MISSING', 'OPEN', NOW(), NOW(), 0
  FROM nx_deposit_order d
  JOIN nx_wallet_ledger l
    ON l.id=d.ledger_id AND l.is_deleted=0 AND l.user_id=d.user_id
   AND l.biz_no=d.deposit_no AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
   AND l.asset=d.asset AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=d.amount
 WHERE d.is_deleted=0 AND d.asset='USDT' AND d.status IN ('CONFIRMED','CREDITED','SUCCESS')
   AND l.user_id=d.user_id AND l.asset='USDT' AND l.direction='IN'
   AND l.status='SUCCESS' AND l.amount=d.amount
   AND NOT EXISTS (SELECT 1 FROM nx_treasury_reserve_ledger t
                    WHERE t.is_deleted=0 AND t.voucher_no=d.deposit_no
                      AND t.direction='IN' AND t.status='CONFIRMED' AND t.amount_usd=d.amount)
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

UPDATE nx_topup_cumulative_backfill_anomaly a
JOIN nx_deposit_order d ON a.source_type='DEPOSIT' AND a.source_no=d.deposit_no
JOIN nx_treasury_reserve_ledger t
  ON t.voucher_no=d.deposit_no AND t.direction='IN' AND t.status='CONFIRMED'
 AND t.amount_usd=d.amount AND t.is_deleted=0
SET a.status='RESOLVED', a.updated_at=NOW()
WHERE a.reason_code IN ('TREASURY_RESERVE_EVIDENCE_MISSING','TREASURY_RESERVE_EVIDENCE_MISMATCH')
  AND a.is_deleted=0
  AND NOT EXISTS (
    SELECT 1 FROM nx_treasury_reserve_ledger bad
     WHERE bad.voucher_no=d.deposit_no AND bad.is_deleted=0
       AND NOT (bad.direction='IN' AND bad.status='CONFIRMED' AND bad.amount_usd=d.amount));

SET @d1_backfill_first_run = IF((SELECT COUNT(*) FROM nx_topup_cumulative_backfill_run
  WHERE migration_key='20260720_d1_verified_v1')=0, 1, 0);

UPDATE nx_payment_record p
JOIN (
  SELECT p0.id AS payment_id, MIN(l0.id) AS ledger_id
    FROM nx_payment_record p0
    JOIN nx_wallet_ledger l0
      ON l0.user_id=p0.user_id AND l0.biz_no=p0.payment_no AND l0.biz_type='CARD_TOPUP'
     AND l0.asset='USDT' AND l0.direction='IN' AND l0.status='SUCCESS'
     AND l0.amount=p0.amount_usdt AND l0.is_deleted=0
   WHERE p0.is_deleted=0
   GROUP BY p0.id HAVING COUNT(*)=1
) exact_binding ON exact_binding.payment_id=p.id
SET p.wallet_ledger_id=exact_binding.ledger_id, p.updated_at=NOW()
WHERE p.is_deleted=0 AND p.wallet_ledger_id IS NULL;

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt, 'DUPLICATE_D4_BINDING', 'OPEN', NOW(), NOW(), 0
  FROM nx_payment_record p
  JOIN nx_wallet_ledger l
    ON l.user_id=p.user_id AND l.biz_no=p.payment_no AND l.biz_type='CARD_TOPUP'
   AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS'
   AND l.amount=p.amount_usdt AND l.is_deleted=0
 WHERE p.is_deleted=0
 GROUP BY p.id,p.payment_no,p.user_id,p.amount_usdt HAVING COUNT(*)>1
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'PAYMENT', p.payment_no, p.user_id, p.amount_usdt, 'UNVERIFIED_D4_BINDING', 'OPEN', NOW(), NOW(), 0
  FROM nx_payment_record p
 WHERE p.is_deleted=0 AND p.wallet_ledger_id IS NULL
   AND p.payment_status IN ('CONFIRMED','PAID','SUCCESS','CHARGEBACK','DISPUTED','CHARGEBACK_REVIEW',
                            'CHARGEBACK_REFUNDED','CHARGEBACK_RECOVERED','CHARGEBACK_PARTIAL')
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'DEPOSIT', d.deposit_no, d.user_id, d.amount,
       IF(d.ledger_id IS NULL, 'UNVERIFIED_D4_BINDING', 'WRONG_DEPOSIT_D4_BINDING'),
       'OPEN', NOW(), NOW(), 0
  FROM nx_deposit_order d
  LEFT JOIN nx_wallet_ledger l
    ON l.id=d.ledger_id AND l.user_id=d.user_id AND l.biz_no=d.deposit_no
   AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
   AND l.asset=d.asset AND l.direction='IN' AND l.status='SUCCESS'
   AND l.amount=d.amount AND l.is_deleted=0
 WHERE d.is_deleted=0 AND d.asset IN ('USDT','BTC','ETH')
   AND d.status IN ('CONFIRMED','CREDITED','SUCCESS')
   AND l.id IS NULL
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), amount_usdt=VALUES(amount_usdt),
  status='OPEN', updated_at=NOW(), is_deleted=0;

-- Close stale binding anomalies only when the current source has one exact canonical D4 entry.
UPDATE nx_topup_cumulative_backfill_anomaly a
JOIN nx_payment_record p ON a.source_type='PAYMENT' AND a.source_no=p.payment_no AND p.is_deleted=0
JOIN (
  SELECT p0.id AS payment_id, MIN(l0.id) AS ledger_id
    FROM nx_payment_record p0
    JOIN nx_wallet_ledger l0
      ON l0.user_id=p0.user_id AND l0.biz_no=p0.payment_no AND l0.biz_type='CARD_TOPUP'
     AND l0.asset='USDT' AND l0.direction='IN' AND l0.status='SUCCESS'
     AND l0.amount=p0.amount_usdt AND l0.is_deleted=0
   WHERE p0.is_deleted=0
   GROUP BY p0.id HAVING COUNT(*)=1
) exact_binding ON exact_binding.payment_id=p.id AND exact_binding.ledger_id=p.wallet_ledger_id
SET a.status='RESOLVED', a.updated_at=NOW()
WHERE a.reason_code IN ('DUPLICATE_D4_BINDING','INVALID_D4_BINDING','UNVERIFIED_D4_BINDING')
  AND a.is_deleted=0;

UPDATE nx_topup_cumulative_backfill_anomaly a
JOIN nx_deposit_order d ON a.source_type='DEPOSIT' AND a.source_no=d.deposit_no AND d.is_deleted=0
JOIN nx_wallet_ledger l
  ON l.id=d.ledger_id AND l.user_id=d.user_id AND l.biz_no=d.deposit_no
 AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP') AND l.asset=d.asset
 AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=d.amount AND l.is_deleted=0
SET a.status='RESOLVED', a.updated_at=NOW()
WHERE a.reason_code IN ('UNVERIFIED_D4_BINDING','WRONG_DEPOSIT_D4_BINDING') AND a.is_deleted=0;

-- Materialize the exact source set once so the write, anomaly checks and run marker
-- cannot silently diverge. One D4 ledger can contribute at most once.
DROP TEMPORARY TABLE IF EXISTS tmp_d1_verified_topup_credit;
CREATE TEMPORARY TABLE tmp_d1_verified_topup_credit AS
SELECT p.wallet_ledger_id AS ledger_id, p.user_id, p.amount_usdt
  FROM nx_payment_record p
  JOIN nx_wallet_ledger l ON l.id=p.wallet_ledger_id
 WHERE p.is_deleted=0 AND l.is_deleted=0 AND l.biz_type='CARD_TOPUP'
   AND p.payment_status IN ('CONFIRMED','PAID','SUCCESS','CHARGEBACK','DISPUTED','CHARGEBACK_REVIEW',
                            'CHARGEBACK_REFUNDED','CHARGEBACK_RECOVERED','CHARGEBACK_PARTIAL')
   AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS'
   AND l.user_id=p.user_id AND l.biz_no=p.payment_no AND l.amount=p.amount_usdt
   AND NOT EXISTS (
     SELECT 1 FROM nx_topup_cumulative_backfill_anomaly a
      WHERE a.source_type='PAYMENT' AND a.source_no=p.payment_no
        AND a.reason_code IN ('DUPLICATE_D4_BINDING','INVALID_D4_BINDING','UNVERIFIED_D4_BINDING')
        AND a.status='OPEN' AND a.is_deleted=0)
UNION
SELECT d.ledger_id, d.user_id, d.amount
  FROM nx_deposit_order d
  JOIN nx_wallet_ledger l ON l.id=d.ledger_id
 WHERE d.is_deleted=0 AND d.asset='USDT' AND d.status IN ('CONFIRMED','CREDITED','SUCCESS')
   AND l.is_deleted=0 AND l.user_id=d.user_id AND l.biz_no=d.deposit_no
   AND l.biz_type IN ('CHAIN_TOPUP','DEPOSIT','TOPUP')
   AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS' AND l.amount=d.amount
   AND NOT EXISTS (
     SELECT 1 FROM nx_topup_cumulative_backfill_anomaly a
      WHERE a.source_type='DEPOSIT' AND a.source_no=d.deposit_no
        AND a.reason_code IN ('UNVERIFIED_D4_BINDING','WRONG_DEPOSIT_D4_BINDING')
        AND a.status='OPEN' AND a.is_deleted=0);
ALTER TABLE tmp_d1_verified_topup_credit ADD PRIMARY KEY (ledger_id);

-- A historical cumulative balance with no exact credit evidence must remain visible
-- for manual review even though the canonical wallet value is corrected to zero.
INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'WALLET', CONCAT('USER-',w.user_id), w.user_id, w.cumulative_deposit_usdt,
       'UNBACKED_CUMULATIVE_BALANCE', 'OPEN', NOW(), NOW(), 0
  FROM nx_user_wallet w
  LEFT JOIN tmp_d1_verified_topup_credit v ON v.user_id=w.user_id
 WHERE w.is_deleted=0 AND w.cumulative_deposit_usdt>0 AND v.ledger_id IS NULL
ON DUPLICATE KEY UPDATE amount_usdt=VALUES(amount_usdt), status='OPEN', updated_at=NOW(), is_deleted=0;

-- A verified credit without a wallet cannot be applied; fail readiness instead of
-- reporting a complete backfill while silently dropping the user's amount.
INSERT INTO nx_topup_cumulative_backfill_anomaly
  (source_type, source_no, user_id, amount_usdt, reason_code, status, created_at, updated_at, is_deleted)
SELECT 'WALLET', CONCAT('USER-',v.user_id), v.user_id, SUM(v.amount_usdt),
       'VERIFIED_SOURCE_WALLET_MISSING', 'OPEN', NOW(), NOW(), 0
  FROM tmp_d1_verified_topup_credit v
  LEFT JOIN nx_user_wallet w ON w.user_id=v.user_id AND w.is_deleted=0
 WHERE w.user_id IS NULL
 GROUP BY v.user_id
ON DUPLICATE KEY UPDATE amount_usdt=VALUES(amount_usdt), status='OPEN', updated_at=NOW(), is_deleted=0;

UPDATE nx_user_wallet w
LEFT JOIN (
  SELECT verified.user_id,
         GREATEST(SUM(verified.amount_usdt) - COALESCE(recovered.recovered_amount, 0), 0) AS cumulative_usdt
    FROM tmp_d1_verified_topup_credit verified
    LEFT JOIN (
      SELECT user_id, SUM(amount_usdt) AS recovered_amount
        FROM nx_topup_chargeback_recovery
       WHERE is_deleted=0 AND status IN ('RECOVERED','PARTIAL_ANOMALY')
       GROUP BY user_id
    ) recovered ON recovered.user_id=verified.user_id
   GROUP BY verified.user_id, recovered.recovered_amount
) source ON source.user_id=w.user_id
SET w.cumulative_deposit_usdt=COALESCE(source.cumulative_usdt,0),
    w.version=w.version+1, w.updated_at=NOW()
WHERE w.is_deleted=0
  AND w.cumulative_deposit_usdt<>COALESCE(source.cumulative_usdt,0);

SET @d1_verified_credit_count = (SELECT COUNT(*) FROM tmp_d1_verified_topup_credit);
SET @d1_anomaly_count = (SELECT COUNT(*) FROM nx_topup_cumulative_backfill_anomaly
  WHERE is_deleted=0 AND status='OPEN');

INSERT INTO nx_audit_log
  (service_name, action, resource_type, resource_id, actor_type, actor_username,
   result, risk_level, detail_json, created_at, is_deleted)
SELECT 'nexion-backend', 'D1_CUMULATIVE_BACKFILL', 'SCHEMA_MIGRATION', '20260720_d1_verified_v1',
       'SYSTEM', 'schema-migration', 'SUCCESS',
       IF(@d1_anomaly_count > 0, 'HIGH', 'INFO'),
       JSON_OBJECT('verifiedCreditCount', @d1_verified_credit_count,
                   'anomalyCount', @d1_anomaly_count,
                   'policy', 'exact-ledger-binding-only'), NOW(), 0
 WHERE @d1_backfill_first_run=1;

INSERT IGNORE INTO nx_topup_cumulative_backfill_run
  (migration_key, verified_credit_count, anomaly_count, executed_at)
VALUES ('20260720_d1_verified_v1', @d1_verified_credit_count, @d1_anomaly_count, NOW());
