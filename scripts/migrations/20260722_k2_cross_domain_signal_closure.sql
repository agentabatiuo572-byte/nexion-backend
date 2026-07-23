-- K2 cross-domain closure: govern the three server-authoritative risk signals
-- consumed by H2, F4d/F8 and the A4 event center.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('risk.arbitrage_suspected','risk','risk','OpsRiskService','A4/K4/H8',1,
   '100%',268,'ACTIVE','migration:k2-cross-domain','K2 authoritative arbitrage action signal',0),
  ('risk.trial_cycle_detected','risk','risk','OpsRiskService','A4/H2/K4',1,
   '100%',269,'ACTIVE','migration:k2-cross-domain','K2 projection from H2 authoritative trial.started events',0),
  ('risk.leaderboard_velocity_flagged','risk','risk','OpsRiskService','A4/F4d/F8/K4',1,
   '100%',270,'ACTIVE','migration:k2-cross-domain','K2 authoritative leaderboard velocity review signal',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer=VALUES(producer),
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:k2-cross-domain',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN (
       'risk.arbitrage_suspected','risk.trial_cycle_detected','risk.leaderboard_velocity_flagged')
   AND NOT EXISTS (
       SELECT 1
         FROM (
           SELECT 'risk.arbitrage_suspected' event_name,'row_id' property_name UNION ALL
           SELECT 'risk.arbitrage_suspected','view_key' UNION ALL
           SELECT 'risk.arbitrage_suspected','action' UNION ALL
           SELECT 'risk.arbitrage_suspected','severity' UNION ALL
           SELECT 'risk.arbitrage_suspected','subject_user_ids' UNION ALL
           SELECT 'risk.arbitrage_suspected','cluster_id' UNION ALL
           SELECT 'risk.trial_cycle_detected','row_id' UNION ALL
           SELECT 'risk.trial_cycle_detected','cycle_count' UNION ALL
           SELECT 'risk.trial_cycle_detected','window_days' UNION ALL
           SELECT 'risk.trial_cycle_detected','severity' UNION ALL
           SELECT 'risk.trial_cycle_detected','subject_user_ids' UNION ALL
           SELECT 'risk.trial_cycle_detected','cluster_id' UNION ALL
           SELECT 'risk.leaderboard_velocity_flagged','row_id' UNION ALL
           SELECT 'risk.leaderboard_velocity_flagged','view_key' UNION ALL
           SELECT 'risk.leaderboard_velocity_flagged','action' UNION ALL
           SELECT 'risk.leaderboard_velocity_flagged','severity' UNION ALL
           SELECT 'risk.leaderboard_velocity_flagged','subject_user_ids' UNION ALL
           SELECT 'risk.leaderboard_velocity_flagged','cluster_id'
         ) expected
        WHERE expected.event_name=s.event_name
          AND expected.property_name=p.property_name
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'risk.arbitrage_suspected' event_name,'row_id' property_name,'id' property_type,1 required_field UNION ALL
    SELECT 'risk.arbitrage_suspected','view_key','enum',1 UNION ALL
    SELECT 'risk.arbitrage_suspected','action','enum',1 UNION ALL
    SELECT 'risk.arbitrage_suspected','severity','enum',1 UNION ALL
    SELECT 'risk.arbitrage_suspected','subject_user_ids','json',1 UNION ALL
    SELECT 'risk.arbitrage_suspected','cluster_id','id',0 UNION ALL
    SELECT 'risk.trial_cycle_detected','row_id','id',1 UNION ALL
    SELECT 'risk.trial_cycle_detected','cycle_count','number',1 UNION ALL
    SELECT 'risk.trial_cycle_detected','window_days','number',1 UNION ALL
    SELECT 'risk.trial_cycle_detected','severity','enum',1 UNION ALL
    SELECT 'risk.trial_cycle_detected','subject_user_ids','json',1 UNION ALL
    SELECT 'risk.trial_cycle_detected','cluster_id','id',0 UNION ALL
    SELECT 'risk.leaderboard_velocity_flagged','row_id','id',1 UNION ALL
    SELECT 'risk.leaderboard_velocity_flagged','view_key','enum',1 UNION ALL
    SELECT 'risk.leaderboard_velocity_flagged','action','enum',1 UNION ALL
    SELECT 'risk.leaderboard_velocity_flagged','severity','enum',1 UNION ALL
    SELECT 'risk.leaderboard_velocity_flagged','subject_user_ids','json',1 UNION ALL
    SELECT 'risk.leaderboard_velocity_flagged','cluster_id','id',0
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,270)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,270);
