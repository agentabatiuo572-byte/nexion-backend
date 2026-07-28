-- H5 daily check-in closure.
-- Aligns the persisted product configuration with the current NEX-only product,
-- replaces the legacy point event contract, and registers streak-saver facts.
-- Safe to rerun.
SET NAMES utf8mb4;

UPDATE nx_growth_checkin_rule
   SET current_value='2',updated_at=NOW()
 WHERE rule_key='baseline' AND current_value='1' AND is_deleted=0;

UPDATE nx_streak_milestone
   SET reward_type='NEX',
       reward_name=CONCAT('+', CAST(reward_amount AS UNSIGNED), ' NEX'),
       updated_at=NOW()
 WHERE milestone_day IN (3,7)
   AND UPPER(reward_type)='POINTS'
   AND is_deleted=0;

SET @h5_daily_revision = 278;
SET @h5_saver_revision = 279;
SET @h5_power_revision = 280;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('daily.checkin','daily','engagement','AppGrowthEngagementService','A4/H5/D4/App',1,
   '100%',@h5_daily_revision,'ACTIVE','migration:h5-daily-nex','migration:h5-daily-nex',
   'H5 authoritative NEX check-in result',0),
  ('daily.streak_restored','daily','engagement','AppGrowthEngagementService','A4/H5/App',1,
   '100%',@h5_saver_revision,'ACTIVE','migration:h5-daily-nex','migration:h5-daily-nex',
   'H5 authoritative streak-saver consumption',0),
  ('daily.power_up_activated','daily','engagement','AppGrowthEngagementService','A4/H5/App',1,
   '100%',@h5_power_revision,'ACTIVE','migration:h5-daily-nex','migration:h5-daily-nex',
   'H5 authoritative streak power-up activation',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer=VALUES(producer),
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',
  updated_by='migration:h5-daily-nex',reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('daily.checkin','daily.streak_restored','daily.power_up_activated')
   AND NOT EXISTS (
     SELECT 1
       FROM (
         SELECT 'daily.checkin' event_name,'check_in_date' property_name UNION ALL
         SELECT 'daily.checkin','base_nex' UNION ALL
         SELECT 'daily.checkin','reward_nex' UNION ALL
         SELECT 'daily.checkin','streak_bonus_nex' UNION ALL
         SELECT 'daily.checkin','multiplier' UNION ALL
         SELECT 'daily.checkin','streak_days' UNION ALL
         SELECT 'daily.streak_restored','restored_streak' UNION ALL
         SELECT 'daily.streak_restored','streak_savers' UNION ALL
         SELECT 'daily.streak_restored','effective_last_check_in_date' UNION ALL
         SELECT 'daily.power_up_activated','power_up_id' UNION ALL
         SELECT 'daily.power_up_activated','power_up_code' UNION ALL
         SELECT 'daily.power_up_activated','badge_code' UNION ALL
         SELECT 'daily.power_up_activated','status'
       ) expected
      WHERE expected.event_name=s.event_name
        AND expected.property_name=p.property_name
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'daily.checkin' event_name,'check_in_date' property_name,'string' property_type UNION ALL
    SELECT 'daily.checkin','base_nex','number' UNION ALL
    SELECT 'daily.checkin','reward_nex','number' UNION ALL
    SELECT 'daily.checkin','streak_bonus_nex','number' UNION ALL
    SELECT 'daily.checkin','multiplier','number' UNION ALL
    SELECT 'daily.checkin','streak_days','number' UNION ALL
    SELECT 'daily.streak_restored','restored_streak','number' UNION ALL
    SELECT 'daily.streak_restored','streak_savers','number' UNION ALL
    SELECT 'daily.streak_restored','effective_last_check_in_date','string' UNION ALL
    SELECT 'daily.power_up_activated','power_up_id','id' UNION ALL
    SELECT 'daily.power_up_activated','power_up_code','id' UNION ALL
    SELECT 'daily.power_up_activated','badge_code','id' UNION ALL
    SELECT 'daily.power_up_activated','status','enum'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('daily','daily.checkin','AppGrowthEngagementService','A4/H5/D4/App',
   'REGISTERED','migration:h5-daily-nex','H5 NEX check-in lifecycle',0),
  ('daily','daily.streak_restored','AppGrowthEngagementService','A4/H5/App',
   'REGISTERED','migration:h5-daily-nex','H5 streak-saver lifecycle',0),
  ('daily','daily.power_up_activated','AppGrowthEngagementService','A4/H5/App',
   'REGISTERED','migration:h5-daily-nex','H5 power-up lifecycle',0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision)
VALUES (1,@h5_power_revision)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,VALUES(current_revision));
