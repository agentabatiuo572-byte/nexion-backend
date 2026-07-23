USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_g3_schedule_execution (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_date DATE NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_g3_schedule_run_date (run_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('wallet.nex_market.weekly_curve',
   '[{"dayIndex":0,"targetPrice":0.12000000,"pumpProbability":0.0500,"volatilityPct":2.0000},{"dayIndex":1,"targetPrice":0.12200000,"pumpProbability":0.0600,"volatilityPct":2.0000},{"dayIndex":2,"targetPrice":0.12400000,"pumpProbability":0.0700,"volatilityPct":2.2000},{"dayIndex":3,"targetPrice":0.12600000,"pumpProbability":0.0800,"volatilityPct":2.2000},{"dayIndex":4,"targetPrice":0.12800000,"pumpProbability":0.0900,"volatilityPct":2.4000},{"dayIndex":5,"targetPrice":0.12600000,"pumpProbability":0.0600,"volatilityPct":2.3000},{"dayIndex":6,"targetPrice":0.12400000,"pumpProbability":0.0500,"volatilityPct":2.1000}]',
   'JSON','wallet','ADMIN','G3 canonical strict seven-frame weekly curve',1,0)
ON DUPLICATE KEY UPDATE
  config_value=IF(JSON_VALID(config_value),
    IF(JSON_LENGTH(CAST(config_value AS JSON))=7,config_value,VALUES(config_value)),
    VALUES(config_value)),
  value_type='JSON',config_group='wallet',visibility='ADMIN',remark=VALUES(remark),status=1,is_deleted=0;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
 ('exchange.swapped','market','exchange','server','G2/D1/D3/D4/K5/A4/App',1,'100%',76,'ACTIVE','migration:g2-g3','Server canonical atomic exchange',0),
 ('exchange.gated','market','exchange','server','G2/K5/J2/A4/App',1,'100%',77,'ACTIVE','migration:g2-g3','Server canonical exchange gate decision',0),
 ('exchange.queue_cancelled','market','exchange','server','G2/D4/A4/App',1,'100%',78,'ACTIVE','migration:g2-g3','User queued exchange cancellation',0),
 ('admin.exchange_caps_changed','admin','phase_admin','server','G2/B1/A2/A4',1,'100%',79,'ACTIVE','migration:g2-g3','G2 caps or queue parameter changed',0),
 ('admin.exchange_fee_changed','admin','phase_admin','server','G2/D1/G3/A2/A4',1,'100%',80,'ACTIVE','migration:g2-g3','G2 exchange fee changed',0),
 ('admin.exchange_paused','admin','phase_admin','server','G2/J1/J2/A2/A4',1,'100%',81,'ACTIVE','migration:g2-g3','G2 kill-only exchange pause',0),
 ('admin.exchange_queue_cancelled','admin','phase_admin','server','G2/D4/A2/A4',1,'100%',82,'ACTIVE','migration:g2-g3','Admin queue cancellation',0),
 ('admin.exchange_kyc_review_requested','admin','phase_admin','server','G2/K5/C4/A2/A4',1,'100%',83,'ACTIVE','migration:g2-g3','Admin KYC review request',0),
 ('admin.exchange_queue_batch_processed','admin','phase_admin','server','G2/D3/D4/A2/A4',1,'100%',84,'ACTIVE','migration:g2-g3','Daily queued exchanges processed atomically',0),
 ('admin.nex_price_curve_changed','admin','phase_admin','server','G3/B1/G2/G7/A2/A4',1,'100%',85,'ACTIVE','migration:g2-g3','Strict weekly curve changed',0),
 ('admin.market_schedule_changed','admin','phase_admin','server','G3/A2/A4',1,'100%',86,'ACTIVE','migration:g2-g3','G3 schedule control changed',0),
 ('admin.nex_oracle_source_changed','admin','phase_admin','server','G3/G2/G7/A2/A4',1,'100%',87,'ACTIVE','migration:g2-g3','G3 oracle source changed',0),
 ('admin.nex_price_overridden','admin','phase_admin','server','G3/G2/G7/B1/A2/A4',1,'100%',88,'ACTIVE','migration:g2-g3','G3 price override',0),
 ('admin.nex_market_override_changed','admin','phase_admin','server','G3/A2/A4',1,'100%',89,'ACTIVE','migration:g2-g3','G3 non-price override changed',0),
 ('admin.market_paused','admin','phase_admin','server','G3/J1/G2/G7/A2/A4',1,'100%',93,'ACTIVE','migration:g2-g3','G3 scheduler paused',0),
 ('admin.market_resumed','admin','phase_admin','server','G3/J1/G2/G7/B1/A2/A4',1,'100%',91,'ACTIVE','migration:g2-g3','G3 scheduler resumed',0),
 ('market.curve_advanced','market','market','server','G3/G2/G7/B1/A4',1,'100%',92,'ACTIVE','migration:g2-g3','Persistently idempotent curve advancement',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer='server',
 consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
 current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:g2-g3',reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
SET p.is_deleted=1,p.updated_at=NOW()
WHERE s.event_name IN (
 'exchange.swapped','exchange.gated','exchange.queue_cancelled','admin.exchange_caps_changed',
 'admin.exchange_fee_changed','admin.exchange_paused','admin.exchange_queue_cancelled',
 'admin.exchange_kyc_review_requested','admin.nex_price_curve_changed','admin.market_schedule_changed',
 'admin.exchange_queue_batch_processed',
 'admin.nex_oracle_source_changed','admin.nex_price_overridden','admin.nex_market_override_changed',
 'admin.market_paused','admin.market_resumed','market.curve_advanced');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
FROM nx_event_schema_registry s
JOIN (
 SELECT 'exchange.swapped' event_name,'exchange_no' property_name,'id' property_type,1 required_field UNION ALL
 SELECT 'exchange.swapped','from_asset','string',1 UNION ALL SELECT 'exchange.swapped','to_asset','string',1 UNION ALL
 SELECT 'exchange.swapped','from_amount','number',1 UNION ALL SELECT 'exchange.swapped','to_amount','number',1 UNION ALL
 SELECT 'exchange.swapped','rate','number',1 UNION ALL SELECT 'exchange.swapped','gross_usdt','number',1 UNION ALL
 SELECT 'exchange.swapped','fee_usdt','number',1 UNION ALL SELECT 'exchange.swapped','status','string',1 UNION ALL
 SELECT 'exchange.gated','exchange_no','id',1 UNION ALL SELECT 'exchange.gated','gate','string',1 UNION ALL
 SELECT 'exchange.gated','status','string',1 UNION ALL SELECT 'exchange.gated','gross_usdt','number',1 UNION ALL
 SELECT 'exchange.queue_cancelled','exchange_no','id',1 UNION ALL SELECT 'exchange.queue_cancelled','status','string',1 UNION ALL SELECT 'exchange.queue_cancelled','cancelled_by','string',1 UNION ALL
 SELECT 'admin.exchange_caps_changed','field','string',1 UNION ALL SELECT 'admin.exchange_caps_changed','value','string',1 UNION ALL SELECT 'admin.exchange_caps_changed','reason','string',1 UNION ALL SELECT 'admin.exchange_caps_changed','operator','string',1 UNION ALL
 SELECT 'admin.exchange_fee_changed','field','string',1 UNION ALL SELECT 'admin.exchange_fee_changed','value','string',1 UNION ALL SELECT 'admin.exchange_fee_changed','reason','string',1 UNION ALL SELECT 'admin.exchange_fee_changed','operator','string',1 UNION ALL
 SELECT 'admin.exchange_paused','reason','string',1 UNION ALL SELECT 'admin.exchange_paused','operator','string',1 UNION ALL SELECT 'admin.exchange_paused','geo_block','json',1 UNION ALL SELECT 'admin.exchange_paused','trigger_basis','string',1 UNION ALL
 SELECT 'admin.exchange_queue_cancelled','exchange_no','id',1 UNION ALL SELECT 'admin.exchange_queue_cancelled','reason','string',1 UNION ALL SELECT 'admin.exchange_queue_cancelled','operator','string',1 UNION ALL
 SELECT 'admin.exchange_kyc_review_requested','exchange_no','id',1 UNION ALL SELECT 'admin.exchange_kyc_review_requested','reason','string',1 UNION ALL SELECT 'admin.exchange_kyc_review_requested','operator','string',1 UNION ALL
 SELECT 'admin.exchange_queue_batch_processed','completed_count','number',1 UNION ALL SELECT 'admin.exchange_queue_batch_processed','skipped_count','number',1 UNION ALL SELECT 'admin.exchange_queue_batch_processed','reason','string',1 UNION ALL SELECT 'admin.exchange_queue_batch_processed','operator','string',1 UNION ALL
 SELECT 'admin.nex_price_curve_changed','frame_count','number',1 UNION ALL SELECT 'admin.nex_price_curve_changed','reason','string',1 UNION ALL SELECT 'admin.nex_price_curve_changed','operator','string',1 UNION ALL
 SELECT 'admin.market_schedule_changed','field','string',1 UNION ALL SELECT 'admin.market_schedule_changed','value','string',1 UNION ALL SELECT 'admin.market_schedule_changed','reason','string',1 UNION ALL SELECT 'admin.market_schedule_changed','operator','string',1 UNION ALL
 SELECT 'admin.nex_oracle_source_changed','field','string',1 UNION ALL SELECT 'admin.nex_oracle_source_changed','value','string',1 UNION ALL SELECT 'admin.nex_oracle_source_changed','reason','string',1 UNION ALL SELECT 'admin.nex_oracle_source_changed','operator','string',1 UNION ALL
 SELECT 'admin.nex_price_overridden','field','string',1 UNION ALL SELECT 'admin.nex_price_overridden','value','string',1 UNION ALL SELECT 'admin.nex_price_overridden','reason','string',1 UNION ALL SELECT 'admin.nex_price_overridden','operator','string',1 UNION ALL
 SELECT 'admin.nex_market_override_changed','field','string',1 UNION ALL SELECT 'admin.nex_market_override_changed','value','string',1 UNION ALL SELECT 'admin.nex_market_override_changed','reason','string',1 UNION ALL SELECT 'admin.nex_market_override_changed','operator','string',1 UNION ALL
 SELECT 'admin.market_paused','field','string',1 UNION ALL SELECT 'admin.market_paused','value','string',1 UNION ALL SELECT 'admin.market_paused','reason','string',1 UNION ALL SELECT 'admin.market_paused','operator','string',1 UNION ALL
 SELECT 'admin.market_resumed','field','string',1 UNION ALL SELECT 'admin.market_resumed','value','string',1 UNION ALL SELECT 'admin.market_resumed','reason','string',1 UNION ALL SELECT 'admin.market_resumed','operator','string',1 UNION ALL
 SELECT 'market.curve_advanced','mode','string',1 UNION ALL SELECT 'market.curve_advanced','reason','string',0 UNION ALL SELECT 'market.curve_advanced','operator','string',0 UNION ALL
 SELECT 'market.curve_advanced','run_date','string',0 UNION ALL SELECT 'market.curve_advanced','before_day_index','number',0 UNION ALL SELECT 'market.curve_advanced','after_day_index','number',0
) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
FROM nx_event_schema_registry s
JOIN (
 SELECT 'trigger_type' property_name,'string' property_type,0 required_field UNION ALL
 SELECT 'user_no','id',0 UNION ALL SELECT 'amount_usdt','number',0 UNION ALL
 SELECT 'cumulative_usdt','number',0 UNION ALL SELECT 'threshold_usdt','number',0 UNION ALL
 SELECT 'exchange_no','id',0
) p
WHERE s.event_name='risk.kyc_review_triggered'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,
 required_field=VALUES(required_field),registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension(domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
SELECT s.owner_domain,s.event_name,'G2G3Closure','G2/G3/A4','REGISTERED','migration:g2-g3','G2 G3 acceptance closure',0
FROM nx_event_schema_registry s
WHERE s.event_name IN ('exchange.swapped','exchange.gated','market.curve_advanced','admin.exchange_paused','admin.nex_price_curve_changed')
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision(id,current_revision) VALUES(1,93)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,93);
