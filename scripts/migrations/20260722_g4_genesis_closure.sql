USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- The old seed value sold_supply=847 had no orders or holdings behind it.  The
-- entitlement table is the canonical sold truth; never manufacture users or balances.
UPDATE nx_genesis_series s
   SET s.sold_supply=(SELECT COUNT(*) FROM nx_genesis_holding h
                       WHERE h.series_code=s.series_code AND h.is_deleted=0),
       s.updated_at=NOW()
 WHERE s.is_deleted=0;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_genesis_holding' AND COLUMN_NAME='listing_price_usdt')=0,
  'ALTER TABLE nx_genesis_holding ADD COLUMN listing_price_usdt DECIMAL(18,6) NULL AFTER status','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_genesis_holding' AND COLUMN_NAME='listed_at')=0,
  'ALTER TABLE nx_genesis_holding ADD COLUMN listed_at DATETIME NULL AFTER listing_price_usdt','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_genesis_order' AND COLUMN_NAME='order_type')=0,
  'ALTER TABLE nx_genesis_order ADD COLUMN order_type VARCHAR(16) NOT NULL DEFAULT ''PRIMARY'' AFTER status','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_genesis_order' AND COLUMN_NAME='seller_user_id')=0,
  'ALTER TABLE nx_genesis_order ADD COLUMN seller_user_id BIGINT NULL AFTER order_type','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_genesis_order' AND COLUMN_NAME='holding_no')=0,
  'ALTER TABLE nx_genesis_order ADD COLUMN holding_no VARCHAR(128) NULL AFTER seller_user_id','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='nx_genesis_order' AND COLUMN_NAME='royalty_usdt')=0,
  'ALTER TABLE nx_genesis_order ADD COLUMN royalty_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER holding_no','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_genesis_emission_batch (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_no VARCHAR(64) NOT NULL,
  snapshot_at DATETIME NOT NULL,
  daily_rate_pct DECIMAL(10,6) NOT NULL,
  holder_count INT NOT NULL DEFAULT 0,
  total_amount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
  operator VARCHAR(96) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  decision_ref VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_genesis_emission_batch (batch_no),
  CONSTRAINT chk_genesis_emission_batch_values CHECK (holder_count>=0 AND total_amount_usdt>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_genesis_emission_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_no VARCHAR(64) NOT NULL,
  holding_no VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  wallet_ledger_id BIGINT NULL,
  paid_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_genesis_emission_item (batch_no,holding_no),
  KEY idx_genesis_emission_user (user_id,created_at),
  CONSTRAINT chk_genesis_emission_item_amount CHECK (amount_usdt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('genesis.purchased','market','genesis','server','G4/B2/D3/D4/A2/A4/App',1,'100%',65,'ACTIVE','migration:g4-genesis','Genesis primary purchase completed',0),
  ('genesis.listed','market','genesis','server','G4/J1/J2/A4/App',1,'100%',66,'ACTIVE','migration:g4-genesis','Genesis internal P2P listing opened',0),
  ('genesis.listing_cancelled','market','genesis','server','G4/A4/App',1,'100%',67,'ACTIVE','migration:g4-genesis','Genesis internal P2P listing cancelled',0),
  ('genesis.secondary_traded','market','genesis','server','G4/D4/A4/App',1,'100%',68,'ACTIVE','migration:g4-genesis','Genesis internal P2P trade completed',0)
  ,('admin.genesis_param_changed','market','genesis','server','G4/B1/A4',1,'100%',69,'ACTIVE','migration:g4-genesis','G4 parameter changed immediately',0)
  ,('admin.genesis_market_paused','market','genesis','server','G4/J1/A4',1,'100%',70,'ACTIVE','migration:g4-genesis','G4 market paused through J1 linked boundary',0)
  ,('admin.genesis_emission_batch_rerun','market','genesis','server','G4/H1/D4/A4',1,'100%',71,'ACTIVE','migration:g4-genesis','G4 emission batch replayed without double paying',0)
  ,('genesis.dividend_paid','market','genesis','server','G4/B2/D3/D4/A4/App',1,'100%',97,'ACTIVE','migration:g4-genesis','Genesis per-holding daily dividend paid',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer='server',
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:g4-genesis',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('genesis.purchased','genesis.listed','genesis.listing_cancelled','genesis.secondary_traded',
                        'admin.genesis_param_changed','admin.genesis_market_paused','admin.genesis_emission_batch_rerun',
                        'genesis.dividend_paid');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,x.property_name,x.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s JOIN (
    SELECT 'genesis.purchased' event_name,'order_no' property_name,'id' property_type UNION ALL
    SELECT 'genesis.purchased','series_code','id' UNION ALL SELECT 'genesis.purchased','quantity','number' UNION ALL
    SELECT 'genesis.purchased','unit_price_usdt','number' UNION ALL SELECT 'genesis.purchased','amount_usdt','number' UNION ALL
    SELECT 'genesis.purchased','wallet_balance_usdt','number' UNION ALL
    SELECT 'genesis.listed','holding_no','id' UNION ALL SELECT 'genesis.listed','ask_price_usdt','number' UNION ALL
    SELECT 'genesis.listing_cancelled','holding_no','id' UNION ALL SELECT 'genesis.listing_cancelled','status','string' UNION ALL
    SELECT 'genesis.secondary_traded','order_no','id' UNION ALL SELECT 'genesis.secondary_traded','holding_no','id' UNION ALL
    SELECT 'genesis.secondary_traded','buyer_user_id','id' UNION ALL SELECT 'genesis.secondary_traded','seller_user_id','id' UNION ALL
    SELECT 'genesis.secondary_traded','price_usdt','number' UNION ALL SELECT 'genesis.secondary_traded','royalty_usdt','number' UNION ALL
    SELECT 'genesis.secondary_traded','seller_net_usdt','number' UNION ALL
    SELECT 'admin.genesis_param_changed','param_key','string' UNION ALL SELECT 'admin.genesis_param_changed','new_value','string' UNION ALL
    SELECT 'admin.genesis_param_changed','decision_ref','string' UNION ALL
    SELECT 'admin.genesis_market_paused','trigger_basis','string' UNION ALL SELECT 'admin.genesis_market_paused','disposition_plan','string' UNION ALL
    SELECT 'admin.genesis_emission_batch_rerun','batch_no','id' UNION ALL SELECT 'admin.genesis_emission_batch_rerun','paid_count','number' UNION ALL
    SELECT 'admin.genesis_emission_batch_rerun','total_amount_usdt','number' UNION ALL SELECT 'admin.genesis_emission_batch_rerun','decision_ref','string' UNION ALL
    SELECT 'genesis.dividend_paid','holding_no','id' UNION ALL SELECT 'genesis.dividend_paid','amount_usdt','number' UNION ALL
    SELECT 'genesis.dividend_paid','rate_applied','number' UNION ALL SELECT 'genesis.dividend_paid','paid_at','timestamp'
  ) x ON x.event_name=s.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('market','genesis.purchased','AppGenesisService','G4/B2/D3/D4/A2/A4/App','REGISTERED','migration:g4-genesis','G4 purchase contract',0),
  ('market','genesis.listed','AppGenesisService','G4/J1/J2/A4/App','REGISTERED','migration:g4-genesis','G4 listing contract',0),
  ('market','genesis.listing_cancelled','AppGenesisService','G4/A4/App','REGISTERED','migration:g4-genesis','G4 listing cancellation contract',0),
  ('market','genesis.secondary_traded','AppGenesisService','G4/D4/A4/App','REGISTERED','migration:g4-genesis','G4 internal trade contract',0)
  ,('market','admin.genesis_param_changed','G4AdminCommandService','G4/B1/A4','REGISTERED','migration:g4-genesis','G4 admin parameter contract',0)
  ,('market','admin.genesis_market_paused','G4AdminCommandService','G4/J1/A4','REGISTERED','migration:g4-genesis','G4 market pause contract',0)
  ,('market','admin.genesis_emission_batch_rerun','G4AdminCommandService','G4/H1/D4/A4','REGISTERED','migration:g4-genesis','G4 emission replay contract',0)
  ,('market','genesis.dividend_paid','G4AdminCommandService','G4/B2/D3/D4/A4/App','REGISTERED','migration:g4-genesis','G4 per-holding emission contract',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,97)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,97);
