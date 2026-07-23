-- H3/H4/H5 user execution closure: trusted quest facts, point rewards,
-- UTC wheel tickets/spins, canonical wheel pool and A4 schemas.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_quest_completion_fact (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  producer VARCHAR(32) NOT NULL,
  event_id VARCHAR(96) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  mission_id BIGINT NOT NULL,
  quest_code VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_quest_completion_fact (producer,event_id),
  KEY idx_growth_quest_completion_user (user_id,quest_code,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_spin_ticket (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(96) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
  used_event_code VARCHAR(64) NULL,
  spin_date DATE NULL,
  used_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_spin_ticket_id (ticket_id),
  UNIQUE KEY uk_growth_spin_ticket_source (user_id,source_type,source_id),
  KEY idx_growth_spin_ticket_user_status (user_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_voucher (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  voucher_id VARCHAR(80) NOT NULL,
  voucher_name VARCHAR(120) NOT NULL,
  voucher_type VARCHAR(20) NOT NULL,
  amount_usd DECIMAL(24,6) NOT NULL DEFAULT 0,
  percent_value DECIMAL(10,4) NOT NULL DEFAULT 0,
  min_purchase_usd DECIMAL(24,6) NOT NULL DEFAULT 0,
  max_discount_usd DECIMAL(24,6) NOT NULL DEFAULT 0,
  applicable_skus JSON NULL,
  audience VARCHAR(30) NOT NULL,
  start_at BIGINT NOT NULL DEFAULT 0,
  end_at BIGINT NOT NULL DEFAULT 0,
  claim_surfaces JSON NULL,
  popup_enabled TINYINT(1) NOT NULL DEFAULT 0,
  stack_with_trial TINYINT(1) NOT NULL DEFAULT 0,
  stack_with_others TINYINT(1) NOT NULL DEFAULT 0,
  splittable TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  created_by VARCHAR(80) NULL,
  updated_by VARCHAR(80) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  UNIQUE KEY uk_nx_growth_voucher_id (voucher_id),
  KEY idx_nx_growth_voucher_status (status),
  KEY idx_nx_growth_voucher_deleted (is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @h4_reward_amount_column := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_wheel_tier' AND COLUMN_NAME='reward_amount'
);
SET @h4_reward_amount_sql := IF(@h4_reward_amount_column=0,
  'ALTER TABLE nx_growth_wheel_tier ADD COLUMN reward_amount DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER reward_kind',
  'SELECT 1');
PREPARE h4_reward_amount_stmt FROM @h4_reward_amount_sql;
EXECUTE h4_reward_amount_stmt;
DEALLOCATE PREPARE h4_reward_amount_stmt;

SET @h4_voucher_id_column := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_wheel_tier' AND COLUMN_NAME='voucher_id'
);
SET @h4_voucher_id_sql := IF(@h4_voucher_id_column=0,
  'ALTER TABLE nx_growth_wheel_tier ADD COLUMN voucher_id VARCHAR(80) NULL AFTER reward_amount',
  'SELECT 1');
PREPARE h4_voucher_id_stmt FROM @h4_voucher_id_sql;
EXECUTE h4_voucher_id_stmt;
DEALLOCATE PREPARE h4_voucher_id_stmt;

SET @h4_daily_stock_column := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_wheel_tier' AND COLUMN_NAME='daily_stock'
);
SET @h4_daily_stock_sql := IF(@h4_daily_stock_column=0,
  'ALTER TABLE nx_growth_wheel_tier ADD COLUMN daily_stock INT NOT NULL DEFAULT 0 AFTER voucher_id',
  'SELECT 1');
PREPARE h4_daily_stock_stmt FROM @h4_daily_stock_sql;
EXECUTE h4_daily_stock_stmt;
DEALLOCATE PREPARE h4_daily_stock_stmt;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_spin (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  spin_no VARCHAR(96) NOT NULL,
  event_id BIGINT NOT NULL,
  event_code VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  spin_date DATE NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  source_id VARCHAR(96) NOT NULL,
  daily_slot TINYINT GENERATED ALWAYS AS (CASE WHEN source_type='DAILY' THEN 1 ELSE NULL END) STORED,
  tier_id BIGINT NOT NULL,
  tier_name VARCHAR(128) NOT NULL,
  reward_kind VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL,
  real_outflow TINYINT NOT NULL DEFAULT 0,
  downgraded TINYINT NOT NULL DEFAULT 0,
  downgrade_reason VARCHAR(64) NOT NULL DEFAULT 'NONE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_wheel_spin_no (spin_no),
  UNIQUE KEY uk_growth_wheel_spin_source (event_id,user_id,spin_date,source_type,source_id),
  UNIQUE KEY uk_growth_wheel_spin_daily (event_id,user_id,spin_date,daily_slot),
  KEY idx_growth_wheel_spin_budget (spin_date,real_outflow),
  KEY idx_growth_wheel_spin_stock (spin_date,tier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO nx_admin_operation_mutex (lock_key) VALUES ('H4_WHEEL_PAYOUT');

-- H5 check-in must have an authoritative DAILY mission. Existing databases may
-- only contain test or weekly missions, so keep this seed deterministic and
-- idempotent instead of relying on environment-specific fixture data.
INSERT INTO nx_mission
  (mission_code,mission_name,mission_type,reward_points,status,created_at,updated_at,is_deleted)
VALUES
  ('DAILY_CHECK_IN','Daily Check-in','DAILY',0,1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  mission_name=VALUES(mission_name),mission_type='DAILY',reward_points=0,
  status=1,updated_at=NOW(),is_deleted=0;

INSERT INTO nx_event_quest
  (quest_code,quest_name,description,geo_scope,starts_at,ends_at,target_type,target_value,
   reward_type,reward_amount,reward_name,badge_achievement_code,sort_order,status,is_deleted)
VALUES
  ('evt-spring-spin','Daily Lucky Spin','One free spin per UTC day plus H5 bonus tickets','',
   NULL,NULL,'wheel',1,'NEX',0,'Wheel pool award',NULL,10,1,0)
ON DUPLICATE KEY UPDATE
  quest_name=VALUES(quest_name),description=VALUES(description),target_type='wheel',
  reward_name=VALUES(reward_name),status=1,is_deleted=0;

INSERT INTO nx_growth_wheel_tier
  (tier_name,reward_name,probability_pct,real_outflow,reward_kind,reward_amount,voucher_id,
   daily_stock,sort_order,status,is_deleted)
VALUES
  ('comfort-nex-5','+5 NEX',38.0000,0,'nex',5,NULL,0,10,1,0),
  ('points-50','+50 points',24.0000,0,'points',50,NULL,0,20,1,0),
  ('nex-30','+30 NEX',18.0000,0,'nex',30,NULL,0,30,1,0),
  ('nex-150','+150 NEX',11.0000,0,'nex',150,NULL,0,40,1,0),
  ('usdt-1','$1 USDT',5.0000,1,'usdt',1,NULL,0,50,1,0),
  ('coupon-50','$50 device coupon',3.0000,0,'coupon',50,'H4-WHEEL-DEVICE-50',200,60,1,0),
  ('usdt-20','$20 USDT',0.9000,1,'usdt',20,NULL,50,70,1,0),
  ('usdt-500','$500 USDT',0.1000,1,'usdt',500,NULL,5,80,1,0)
ON DUPLICATE KEY UPDATE
  reward_name=VALUES(reward_name),probability_pct=VALUES(probability_pct),
  real_outflow=VALUES(real_outflow),reward_kind=VALUES(reward_kind),
  reward_amount=VALUES(reward_amount),voucher_id=VALUES(voucher_id),
  daily_stock=VALUES(daily_stock),sort_order=VALUES(sort_order),status=1,is_deleted=0;

-- nx_growth_wheel_tier is the single canonical pool (there is no event_id on
-- the legacy table). Retire obsolete rows so active probabilities remain
-- exactly 100% instead of silently combining old and new pools.
UPDATE nx_growth_wheel_tier
   SET status=0,is_deleted=1,updated_at=NOW()
 WHERE tier_name NOT IN (
   'comfort-nex-5','points-50','nex-30','nex-150',
   'usdt-1','coupon-50','usdt-20','usdt-500'
 );

INSERT INTO nx_growth_wheel_guard
  (guard_key,guard_label,guard_value,note,sort_order,status,is_deleted)
VALUES
  ('budget','Daily real payout budget USD','2000','UTC global real USDT payout cap',10,1,0),
  ('cap','Per-tier daily stock','tier.daily_stock','500 USDT x5; 20 USDT x50; coupon x200',20,1,0),
  ('kill','Real prize enabled','on','off downgrades real tiers to comfort tier',30,1,0)
ON DUPLICATE KEY UPDATE
  guard_label=VALUES(guard_label),note=VALUES(note),sort_order=VALUES(sort_order),status=1,is_deleted=0;

INSERT INTO nx_growth_voucher
  (voucher_id,voucher_name,voucher_type,amount_usd,percent_value,min_purchase_usd,max_discount_usd,
   applicable_skus,audience,start_at,end_at,claim_surfaces,popup_enabled,stack_with_trial,
   stack_with_others,splittable,status,created_by,updated_by,is_deleted)
VALUES
  ('H4-WHEEL-DEVICE-50','Lucky Spin $50 Device Coupon','fixed',50,0,50,50,
   JSON_ARRAY(),'all',0,0,JSON_ARRAY(),0,0,0,0,'active','migration:h4-wheel','migration:h4-wheel',0)
ON DUPLICATE KEY UPDATE
  voucher_name=VALUES(voucher_name),voucher_type='fixed',amount_usd=50,min_purchase_usd=50,
  max_discount_usd=50,status='active',updated_by='migration:h4-wheel',is_deleted=0;

INSERT INTO nx_achievement
  (achievement_code,achievement_name,description,category,icon_key,accent_color,trigger_type,
   trigger_value,reward_points,sort_order,status,is_deleted)
VALUES
  ('STREAK_MASTER','Streak Master','100-day check-in milestone badge','STREAK','streak-master',
   '#F59E0B','STREAK_DAYS',100,0,100,1,0)
ON DUPLICATE KEY UPDATE
  achievement_name=VALUES(achievement_name),description=VALUES(description),category=VALUES(category),
  trigger_type=VALUES(trigger_type),trigger_value=100,status=1,is_deleted=0;

-- Exact A4 schemas for the newly authoritative H3/H4/H5 facts.
INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('quest.completed','quest','engagement','QuestCompletionFactConsumer','A4/H3',1,'100%',104,'ACTIVE','migration:h-engagement-execution','Trusted server quest completion fact',0),
  ('daily.checkin','daily','engagement','AppGrowthEngagementService','A4/H5',1,'100%',105,'ACTIVE','migration:h-engagement-execution','Authoritative point check-in result',0),
  ('daily.spin_awarded','daily','engagement','AppGrowthEngagementService','A4/H5/H4',1,'100%',106,'ACTIVE','migration:h-engagement-execution','H5 bonus spin ticket issuance',0),
  ('event.spin_awarded','event','engagement','AppGrowthWheelService','A4/H4/D4',1,'100%',107,'ACTIVE','migration:h-engagement-execution','H4 wheel reward result',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer=VALUES(producer),
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:h-engagement-execution',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('quest.completed','daily.checkin','daily.spin_awarded','event.spin_awarded')
   AND NOT EXISTS (
     SELECT 1 FROM (
       SELECT 'quest.completed' event_name,'quest_id' property_name UNION ALL
       SELECT 'quest.completed','layer' UNION ALL SELECT 'quest.completed','producer' UNION ALL
       SELECT 'quest.completed','source_event_id' UNION ALL
       SELECT 'daily.checkin','check_in_date' UNION ALL SELECT 'daily.checkin','base_points' UNION ALL
       SELECT 'daily.checkin','reward_points' UNION ALL SELECT 'daily.checkin','multiplier' UNION ALL
       SELECT 'daily.checkin','streak_bonus_points' UNION ALL
       SELECT 'daily.checkin','streak_days' UNION ALL
       SELECT 'daily.spin_awarded','milestone_id' UNION ALL SELECT 'daily.spin_awarded','tickets' UNION ALL
       SELECT 'event.spin_awarded','campaign_id' UNION ALL SELECT 'event.spin_awarded','tier_id' UNION ALL
       SELECT 'event.spin_awarded','reward_type' UNION ALL SELECT 'event.spin_awarded','reward_amount' UNION ALL
       SELECT 'event.spin_awarded','downgraded' UNION ALL SELECT 'event.spin_awarded','source_type'
     ) expected
    WHERE expected.event_name=s.event_name AND expected.property_name=p.property_name
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'quest.completed' event_name,'quest_id' property_name,'id' property_type UNION ALL
    SELECT 'quest.completed','layer','enum' UNION ALL SELECT 'quest.completed','producer','enum' UNION ALL
    SELECT 'quest.completed','source_event_id','id' UNION ALL
    SELECT 'daily.checkin','check_in_date','string' UNION ALL SELECT 'daily.checkin','base_points','number' UNION ALL
    SELECT 'daily.checkin','reward_points','number' UNION ALL SELECT 'daily.checkin','multiplier','number' UNION ALL
    SELECT 'daily.checkin','streak_bonus_points','number' UNION ALL
    SELECT 'daily.checkin','streak_days','number' UNION ALL
    SELECT 'daily.spin_awarded','milestone_id','id' UNION ALL SELECT 'daily.spin_awarded','tickets','number' UNION ALL
    SELECT 'event.spin_awarded','campaign_id','id' UNION ALL SELECT 'event.spin_awarded','tier_id','id' UNION ALL
    SELECT 'event.spin_awarded','reward_type','enum' UNION ALL SELECT 'event.spin_awarded','reward_amount','number' UNION ALL
    SELECT 'event.spin_awarded','downgraded','boolean' UNION ALL SELECT 'event.spin_awarded','source_type','enum'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('quest','quest.completed','QuestCompletionFactConsumer','A4/H3','REGISTERED','migration:h-engagement-execution','H3 completion lifecycle',0),
  ('daily','daily.checkin','AppGrowthEngagementService','A4/H5','REGISTERED','migration:h-engagement-execution','H5 point check-in',0),
  ('daily','daily.spin_awarded','AppGrowthEngagementService','A4/H5/H4','REGISTERED','migration:h-engagement-execution','H5 bonus spin ticket',0),
  ('event','event.spin_awarded','AppGrowthWheelService','A4/H4/D4','REGISTERED','migration:h-engagement-execution','H4 wheel reward',0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,107)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,107);
