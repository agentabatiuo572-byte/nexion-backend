USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Canonical G7 product. Values are snapshots for new orders; in-lock positions are never rewritten.
INSERT INTO nx_staking_product
  (product_code,name,asset,term_days,apy_bps,early_penalty_bps,min_amount,
   reward_multiplier,ticket_per_order,preset_amounts,sort_order,status,is_deleted)
VALUES ('REPURCHASE_90D','复投增益 90 天','USDT',90,3500,1500,100,1.5,1,'100 / 200 / 500 / 1000',700,'ACTIVE',0)
ON DUPLICATE KEY UPDATE name=VALUES(name),asset='USDT',status=IF(status='',VALUES(status),status),is_deleted=0;

CREATE TABLE IF NOT EXISTS nx_g7_repurchase_ticket (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  position_no VARCHAR(96) NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
  issued_at DATETIME NOT NULL,
  forfeited_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_g7_ticket_no (ticket_no),
  UNIQUE KEY uk_g7_ticket_position (position_no),
  KEY idx_g7_ticket_month_status (status,issued_at),
  CONSTRAINT chk_g7_ticket_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,remark,status,is_deleted)
VALUES ('G.genesis.lottery.monthlyCapacity','100000','NUMBER','market',
        'G4 monthly lottery ticket capacity consumed by G7',1,0)
ON DUPLICATE KEY UPDATE is_deleted=0,status=1;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES ('wallet.reinvest','wallet','money','server','G7/B3/B2/D4/A4',1,'100%',72,'ACTIVE',
        'migration:g7-repurchase','Server-canonical G7 atomic repurchase',0),
       ('admin.repurchase_config_changed','admin','phase_admin','server','G7/B1/J1/A2/A4',1,'100%',73,'ACTIVE',
        'migration:g7-repurchase','Direct G7 product parameter command',0),
       ('admin.repurchase_lottery_changed','admin','phase_admin','server','G7/G4/J1/A2/A4',1,'100%',74,'ACTIVE',
        'migration:g7-repurchase','Direct G7 lottery rule command with G4 capacity snapshot',0),
       ('admin.repurchase_params_changed','admin','phase_admin','server','G7/B1/J1/A2/A4',1,'100%',75,'ACTIVE',
        'migration:g7-repurchase','Direct G7 preset or penalty command',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer='server',
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',current_revision=VALUES(current_revision),
  status='ACTIVE',updated_by='migration:g7-repurchase',reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
SET p.is_deleted=1,p.updated_at=NOW()
WHERE s.event_name='wallet.reinvest' AND p.property_name NOT IN
  ('position_no','product','amount_usdt','apy_pct','term_days','unlock_at','ticket_count',
   'nurture_multiplier','wallet_balance_usdt');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,x.property_name,x.property_type,0,1,72,0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'position_no' property_name,'id' property_type UNION ALL
  SELECT 'product','enum' UNION ALL
  SELECT 'amount_usdt','number' UNION ALL
  SELECT 'apy_pct','number' UNION ALL
  SELECT 'term_days','number' UNION ALL
  SELECT 'unlock_at','timestamp' UNION ALL
  SELECT 'ticket_count','number' UNION ALL
  SELECT 'nurture_multiplier','number' UNION ALL
  SELECT 'wallet_balance_usdt','number'
) x ON s.event_name='wallet.reinvest'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=72,is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
SET p.is_deleted=1,p.updated_at=NOW()
WHERE s.event_name IN ('admin.repurchase_config_changed','admin.repurchase_lottery_changed','admin.repurchase_params_changed')
  AND p.property_name NOT IN ('param_key','before','after','coverage_at_submit','g4_capacity',
                              'g4_tickets_issued','g4_ref','reason','operator','idempotency_key');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,x.property_name,x.property_type,0,1,s.current_revision,0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'param_key' property_name,'string' property_type UNION ALL
  SELECT 'before','string' UNION ALL SELECT 'after','string' UNION ALL
  SELECT 'coverage_at_submit','number' UNION ALL SELECT 'g4_capacity','number' UNION ALL
  SELECT 'g4_tickets_issued','number' UNION ALL SELECT 'g4_ref','string' UNION ALL
  SELECT 'reason','string' UNION ALL SELECT 'operator','string' UNION ALL
  SELECT 'idempotency_key','id'
) x ON s.event_name IN ('admin.repurchase_config_changed','admin.repurchase_lottery_changed','admin.repurchase_params_changed')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES ('wallet','wallet.reinvest','AppRepurchaseService','G7/B3/B2/D4/A4','REGISTERED',
        'migration:g7-repurchase','G7 canonical repurchase funnel and money event',0),
       ('repurchase','wallet.reinvest','AppRepurchaseService','G7/B3/A4','REGISTERED',
        'migration:g7-repurchase','Register G7 repurchase product domain',0),
       ('admin','admin.repurchase_config_changed','OpsRepurchaseAdminService','G7/B1/J1/A2/A4','REGISTERED',
        'migration:g7-repurchase','G7 direct product config event',0),
       ('admin','admin.repurchase_lottery_changed','OpsRepurchaseAdminService','G7/G4/J1/A2/A4','REGISTERED',
        'migration:g7-repurchase','G7 lottery change with G4 capacity evidence',0),
       ('admin','admin.repurchase_params_changed','OpsRepurchaseAdminService','G7/B1/J1/A2/A4','REGISTERED',
        'migration:g7-repurchase','G7 direct preset and penalty event',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,75)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,75);
