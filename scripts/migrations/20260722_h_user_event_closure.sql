-- H2-H7 user lifecycle events. Each schema exactly matches the server payload;
-- EventOutboxService therefore fails closed on drift or unregistered fields.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('trial.charge_attempted','trial','conversion','AppCanonicalBoundaryService','A4/H2/D4',1,'100%',91,'ACTIVE','migration:h-user-events','H2 canonical trial charge decision',0),
  ('quest.claimed','quest','engagement','AppGrowthEngagementService','A4/H3/D4',1,'100%',92,'ACTIVE','migration:h-user-events','H3 real quest reward claim',0),
  ('event.joined','event','engagement','AppGrowthEngagementService','A4/H4',1,'100%',93,'ACTIVE','migration:h-user-events','H4 user event enrollment',0),
  ('event.claimed','event','engagement','AppGrowthEngagementService','A4/H4/D4',1,'100%',94,'ACTIVE','migration:h-user-events','H4 real event reward claim',0),
  ('daily.lucky_triggered','daily','engagement','AppGrowthEngagementService','A4/H5',1,'100%',95,'ACTIVE','migration:h-user-events','H5 server RNG lucky reward',0),
  ('daily.milestone_claimed','daily','engagement','AppGrowthEngagementService','A4/H5/D4',1,'100%',96,'ACTIVE','migration:h-user-events','H5 streak milestone reward claim',0),
  ('milestone.fired','milestone','engagement','AppGrowthEngagementService','A4/H5/D4',1,'100%',97,'ACTIVE','migration:h-user-events','H5 lifetime earning milestone settlement',0),
  ('voucher.claimed','voucher','conversion','AppGrowthEngagementService','A4/H7',1,'100%',98,'ACTIVE','migration:h-user-events','H7 real voucher ownership claim',0),
  ('voucher.redeemed','voucher','conversion','AppGrowthLifecyclePublisher','A4/H7/E4/D4',1,'100%',99,'ACTIVE','migration:h-user-events','H7 atomic order voucher consumption',0),
  ('trial.started','trial','conversion','AppTrialLifecycleService','A4/H2',1,'100%',100,'ACTIVE','migration:h-user-events','H2 authoritative trial start',0),
  ('trial.cancelled','trial','conversion','AppTrialLifecycleService','A4/H2',1,'100%',101,'ACTIVE','migration:h-user-events','H2 authoritative trial cancellation',0),
  ('trial.extended','trial','conversion','AppTrialLifecycleService','A4/H2',1,'100%',102,'ACTIVE','migration:h-user-events','H2 authoritative trial extension',0),
  ('trial.redeemed','trial','conversion','AppTrialLifecycleService','A4/H2/E4/D4',1,'100%',103,'ACTIVE','migration:h-user-events','H2 wallet settlement and device redemption',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer=VALUES(producer),
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:h-user-events',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN (
       'trial.charge_attempted','quest.claimed','event.joined','event.claimed',
       'daily.lucky_triggered','daily.milestone_claimed','milestone.fired',
       'voucher.claimed','voucher.redeemed','trial.started','trial.cancelled','trial.extended','trial.redeemed')
   AND NOT EXISTS (
       SELECT 1 FROM (
         SELECT 'trial.charge_attempted' event_name,'trigger' property_name UNION ALL
         SELECT 'trial.charge_attempted','result' UNION ALL
         SELECT 'trial.charge_attempted','amount_usdt' UNION ALL
         SELECT 'trial.charge_attempted','reason' UNION ALL
         SELECT 'trial.charge_attempted','payment_rail' UNION ALL
         SELECT 'quest.claimed','layer' UNION ALL
         SELECT 'quest.claimed','reward_nex' UNION ALL
         SELECT 'quest.claimed','multiplier' UNION ALL
         SELECT 'quest.claimed','rhythm_month' UNION ALL
         SELECT 'event.joined','campaign_id' UNION ALL
         SELECT 'event.claimed','campaign_id' UNION ALL
         SELECT 'event.claimed','reward_type' UNION ALL
         SELECT 'event.claimed','reward_amount' UNION ALL
         SELECT 'event.claimed','badge_code' UNION ALL
         SELECT 'daily.lucky_triggered','multiplier' UNION ALL
         SELECT 'daily.milestone_claimed','day' UNION ALL
         SELECT 'daily.milestone_claimed','reward_type' UNION ALL
         SELECT 'daily.milestone_claimed','amount' UNION ALL
         SELECT 'milestone.fired','milestone_id' UNION ALL
         SELECT 'milestone.fired','threshold_usd' UNION ALL
         SELECT 'milestone.fired','reward_nex' UNION ALL
         SELECT 'milestone.fired','lifetime_earnings_usd' UNION ALL
         SELECT 'voucher.claimed','voucher_id' UNION ALL
         SELECT 'voucher.claimed','surface' UNION ALL
         SELECT 'voucher.claimed','audience' UNION ALL
         SELECT 'voucher.redeemed','voucher_id' UNION ALL
         SELECT 'voucher.redeemed','order_id' UNION ALL
         SELECT 'voucher.redeemed','sku' UNION ALL
         SELECT 'voucher.redeemed','discount_usd' UNION ALL
         SELECT 'trial.started','trial_price_usdt' UNION ALL
         SELECT 'trial.started','trial_days' UNION ALL
         SELECT 'trial.started','payment_rail' UNION ALL
         SELECT 'trial.cancelled','cause' UNION ALL
         SELECT 'trial.cancelled','state_before' UNION ALL
         SELECT 'trial.extended','extension_days' UNION ALL
         SELECT 'trial.extended','shadow_usdt' UNION ALL
         SELECT 'trial.redeemed','shadow_usdt' UNION ALL
         SELECT 'trial.redeemed','shadow_nex' UNION ALL
         SELECT 'trial.redeemed','offset_usdt' UNION ALL
         SELECT 'trial.redeemed','remainder_usdt' UNION ALL
         SELECT 'trial.redeemed','discount_applied' UNION ALL
         SELECT 'trial.redeemed','amount_usdt' UNION ALL
         SELECT 'trial.redeemed','early_purchase' UNION ALL
         SELECT 'trial.redeemed','payment_rail' UNION ALL
         SELECT 'trial.redeemed','device_id'
       ) expected
       WHERE expected.event_name=s.event_name AND expected.property_name=p.property_name);

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'trial.charge_attempted' event_name,'trigger' property_name,'enum' property_type UNION ALL
    SELECT 'trial.charge_attempted','result','enum' UNION ALL
    SELECT 'trial.charge_attempted','amount_usdt','number' UNION ALL
    SELECT 'trial.charge_attempted','reason','enum' UNION ALL
    SELECT 'trial.charge_attempted','payment_rail','enum' UNION ALL
    SELECT 'quest.claimed','layer','enum' UNION ALL
    SELECT 'quest.claimed','reward_nex','number' UNION ALL
    SELECT 'quest.claimed','multiplier','number' UNION ALL
    SELECT 'quest.claimed','rhythm_month','number' UNION ALL
    SELECT 'event.joined','campaign_id','id' UNION ALL
    SELECT 'event.claimed','campaign_id','id' UNION ALL
    SELECT 'event.claimed','reward_type','enum' UNION ALL
    SELECT 'event.claimed','reward_amount','number' UNION ALL
    SELECT 'event.claimed','badge_code','id' UNION ALL
    SELECT 'daily.lucky_triggered','multiplier','number' UNION ALL
    SELECT 'daily.milestone_claimed','day','number' UNION ALL
    SELECT 'daily.milestone_claimed','reward_type','enum' UNION ALL
    SELECT 'daily.milestone_claimed','amount','number' UNION ALL
    SELECT 'milestone.fired','milestone_id','id' UNION ALL
    SELECT 'milestone.fired','threshold_usd','number' UNION ALL
    SELECT 'milestone.fired','reward_nex','number' UNION ALL
    SELECT 'milestone.fired','lifetime_earnings_usd','number' UNION ALL
    SELECT 'voucher.claimed','voucher_id','id' UNION ALL
    SELECT 'voucher.claimed','surface','enum' UNION ALL
    SELECT 'voucher.claimed','audience','enum' UNION ALL
    SELECT 'voucher.redeemed','voucher_id','id' UNION ALL
    SELECT 'voucher.redeemed','order_id','id' UNION ALL
    SELECT 'voucher.redeemed','sku','id' UNION ALL
    SELECT 'voucher.redeemed','discount_usd','number' UNION ALL
    SELECT 'trial.started','trial_price_usdt','number' UNION ALL
    SELECT 'trial.started','trial_days','number' UNION ALL
    SELECT 'trial.started','payment_rail','enum' UNION ALL
    SELECT 'trial.cancelled','cause','string' UNION ALL
    SELECT 'trial.cancelled','state_before','enum' UNION ALL
    SELECT 'trial.extended','extension_days','number' UNION ALL
    SELECT 'trial.extended','shadow_usdt','number' UNION ALL
    SELECT 'trial.redeemed','shadow_usdt','number' UNION ALL
    SELECT 'trial.redeemed','shadow_nex','number' UNION ALL
    SELECT 'trial.redeemed','offset_usdt','number' UNION ALL
    SELECT 'trial.redeemed','remainder_usdt','number' UNION ALL
    SELECT 'trial.redeemed','discount_applied','number' UNION ALL
    SELECT 'trial.redeemed','amount_usdt','number' UNION ALL
    SELECT 'trial.redeemed','early_purchase','boolean' UNION ALL
    SELECT 'trial.redeemed','payment_rail','enum' UNION ALL
    SELECT 'trial.redeemed','device_id','id'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

-- Complete the A4 blocking domain-extension work orders required by H4/H6/H7.
INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('event','event.joined','AppGrowthEngagementService','A4/H4','REGISTERED','migration:h-user-events','H4 event domain user opt-in',0),
  ('event','event.claimed','AppGrowthEngagementService','A4/H4/D4','REGISTERED','migration:h-user-events','H4 event domain reward claim',0),
  ('milestone','milestone.fired','AppGrowthEngagementService','A4/H5/D4','REGISTERED','migration:h-user-events','H6 milestone domain extension',0),
  ('voucher','voucher.claimed','AppGrowthEngagementService','A4/H7','REGISTERED','migration:h-user-events','H7 voucher domain ownership claim',0),
  ('voucher','voucher.redeemed','AppGrowthLifecyclePublisher','A4/H7/E4/D4','REGISTERED','migration:h-user-events','H7 voucher domain order redemption',0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,103)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,103);
