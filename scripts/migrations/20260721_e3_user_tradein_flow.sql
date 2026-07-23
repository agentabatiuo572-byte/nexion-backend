-- E3 user trade-in: server-canonical output ladder, wallet purchase and delivery closure.
-- Idempotent on MySQL 8. Retired age/value configuration is not read or revived.
SET NAMES utf8mb4;

-- A fully discounted trade-in still needs one immutable D4 accounting trace.
-- Keep the historic positive-amount invariant for every other ledger scenario.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA=DATABASE()
                  AND CONSTRAINT_NAME='chk_wallet_ledger_positive_amount'
                  AND LOCATE('TRADE_IN_PURCHASE', UPPER(CHECK_CLAUSE))=0)>0,
  'ALTER TABLE nx_wallet_ledger DROP CHECK chk_wallet_ledger_positive_amount',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA=DATABASE()
                  AND CONSTRAINT_NAME='chk_wallet_ledger_positive_amount')=0,
  'ALTER TABLE nx_wallet_ledger ADD CONSTRAINT chk_wallet_ledger_positive_amount CHECK (amount > 0 OR (amount = 0 AND biz_type = ''TRADE_IN_PURCHASE'' AND asset = ''USDT'' AND direction = ''OUT'' AND status = ''SUCCESS''))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Ensure the user flow has a complete canonical ladder even on a database that only had
-- the historical admin projection. Existing operator-selected values are preserved.
INSERT INTO nx_compute_e3_config
  (config_key,config_value,value_type,updated_by,sort_order,created_at,updated_at,is_deleted)
VALUES
  ('tradeinEnabled','true','BOOLEAN','migration:e3-user-tradein',200,NOW(),NOW(),0),
  ('eligibility','L4+ 持有者','STRING','migration:e3-user-tradein',201,NOW(),NOW(),0),
  ('tradeinLadderCut1','25','NUMBER','migration:e3-user-tradein',202,NOW(),NOW(),0),
  ('tradeinLadderCut2','50','NUMBER','migration:e3-user-tradein',203,NOW(),NOW(),0),
  ('tradeinLadderCut3','75','NUMBER','migration:e3-user-tradein',204,NOW(),NOW(),0),
  ('tradeinLadderCut4','100','NUMBER','migration:e3-user-tradein',205,NOW(),NOW(),0),
  ('tradeinLadderCredit1','75','NUMBER','migration:e3-user-tradein',206,NOW(),NOW(),0),
  ('tradeinLadderCredit2','60','NUMBER','migration:e3-user-tradein',207,NOW(),NOW(),0),
  ('tradeinLadderCredit3','45','NUMBER','migration:e3-user-tradein',208,NOW(),NOW(),0),
  ('tradeinLadderCredit4','30','NUMBER','migration:e3-user-tradein',209,NOW(),NOW(),0),
  ('tradeinLadderCredit5','15','NUMBER','migration:e3-user-tradein',210,NOW(),NOW(),0),
  ('tradeinRequireHigherPrice','true','BOOLEAN','migration:e3-user-tradein',211,NOW(),NOW(),0),
  ('tradeinMaxDevicesPerOrder','3','NUMBER','migration:e3-user-tradein',212,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND COLUMN_NAME='idempotency_key')=0,
  'ALTER TABLE nx_tradein_application ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER reviewed_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND COLUMN_NAME='cumulative_output_usdt')=0,
  'ALTER TABLE nx_tradein_application ADD COLUMN cumulative_output_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER idempotency_key',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND COLUMN_NAME='output_ratio_pct')=0,
  'ALTER TABLE nx_tradein_application ADD COLUMN output_ratio_pct DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER cumulative_output_usdt',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND COLUMN_NAME='credit_rate_pct')=0,
  'ALTER TABLE nx_tradein_application ADD COLUMN credit_rate_pct DECIMAL(10,6) NOT NULL DEFAULT 0 AFTER output_ratio_pct',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND COLUMN_NAME='wallet_debit_usdt')=0,
  'ALTER TABLE nx_tradein_application ADD COLUMN wallet_debit_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER credit_rate_pct',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND COLUMN_NAME='target_order_no')=0,
  'ALTER TABLE nx_tradein_application ADD COLUMN target_order_no VARCHAR(96) NULL AFTER wallet_debit_usdt',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND COLUMN_NAME='target_device_id')=0,
  'ALTER TABLE nx_tradein_application ADD COLUMN target_device_id BIGINT NULL AFTER target_order_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND COLUMN_NAME='completed_at')=0,
  'ALTER TABLE nx_tradein_application ADD COLUMN completed_at DATETIME NULL AFTER target_device_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_tradein_application'
                  AND INDEX_NAME='uk_tradein_user_idempotency')=0,
  'ALTER TABLE nx_tradein_application ADD UNIQUE KEY uk_tradein_user_idempotency (user_id,idempotency_key)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_compute_receipt'
                  AND INDEX_NAME='idx_receipt_device_status')=0,
  'ALTER TABLE nx_compute_receipt ADD KEY idx_receipt_device_status (user_device_id,earning_status,completed_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_earning_event'
                  AND INDEX_NAME='idx_earning_device_asset_status')=0,
  'ALTER TABLE nx_earning_event ADD KEY idx_earning_device_asset_status (user_device_id,asset,status,created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('tradein.completed','device','tradein','server','E3/D4/K2/A4',1,'100%',46,
   'ACTIVE','migration:e3-user-tradein','Server-canonical user trade-in completion',0)
ON DUPLICATE KEY UPDATE
  owner_domain='device', family_key='tradein', producer='server', consumers='E3/D4/K2/A4',
  is_server_authoritative=1, sampling_policy='100%', current_revision=46, status='ACTIVE',
  updated_by='migration:e3-user-tradein', reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name='tradein.completed'
   AND p.property_name NOT IN (
     'tradein_no','source_device_id','target_product_id','target_device_id','order_no',
     'output_ratio_pct','credit_rate_pct','discount_usdt','wallet_debit_usdt');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,46,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'tradein_no' property_name,'id' property_type UNION ALL
    SELECT 'source_device_id','id' UNION ALL
    SELECT 'target_product_id','id' UNION ALL
    SELECT 'target_device_id','id' UNION ALL
    SELECT 'order_no','id' UNION ALL
    SELECT 'output_ratio_pct','number' UNION ALL
    SELECT 'credit_rate_pct','number' UNION ALL
    SELECT 'discount_usdt','number' UNION ALL
    SELECT 'wallet_debit_usdt','number'
  ) p
 WHERE s.event_name='tradein.completed'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=46,is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('device','tradein.completed','AppTradeinService','E3/D4/K2/A4','REGISTERED',
   'migration:e3-user-tradein','User trade-in completion downstream contract',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,46)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,46);
