-- Restore the App event contracts from 20260722_i6_i7_a4_event_closure.sql,
-- 20260722_h_user_event_closure.sql and 20260721_e5_device_operations.sql.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT IGNORE INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('learn.course_completed','learn','learn','AppLearningService','A4/I7/H3/B1/B3/D/L',1,'100%',116,'ACTIVE',
   'migration:i6-i7-a4',NULL,'I7 server-validated quiz completion and NEX reward fact',0),
  ('event.joined','event','engagement','AppGrowthEngagementService','A4/H4',1,'100%',93,'ACTIVE',
   'migration:h-user-events',NULL,'H4 user event enrollment',0),
  ('event.claimed','event','engagement','AppGrowthEngagementService','A4/H4/D4',1,'100%',94,'ACTIVE',
   'migration:h-user-events',NULL,'H4 real event reward claim',0),
  ('admin.device_activated','admin','device-ops','server','E5/A2/A4/L3/H3',1,'100%',51,'ACTIVE',
   'migration:e5-device-operations','migration:h3-trusted-device-activation',
   'E5 trusted server device activation for H3 canonical quest',0);

-- Fill gaps only for active rows whose canonical contract still matches.
CREATE TEMPORARY TABLE tmp_a4_app_event_restore AS
SELECT id,event_name,owner_domain,consumers,current_revision
  FROM nx_event_schema_registry
 WHERE (BINARY event_name,BINARY owner_domain,BINARY family_key,BINARY producer,BINARY consumers,current_revision) IN
       (('learn.course_completed','learn','learn','AppLearningService','A4/I7/H3/B1/B3/D/L',116),
        ('event.joined','event','engagement','AppGrowthEngagementService','A4/H4',93),
        ('event.claimed','event','engagement','AppGrowthEngagementService','A4/H4/D4',94),
        ('admin.device_activated','admin','device-ops','server','E5/A2/A4/L3/H3',51))
   AND is_server_authoritative=1 AND BINARY sampling_policy='100%' AND BINARY status='ACTIVE' AND is_deleted=0;

INSERT IGNORE INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM tmp_a4_app_event_restore s
  JOIN (
    SELECT 116 revision,'course_id' property_name,'id' property_type UNION ALL
    SELECT 116,'course_version','id' UNION ALL
    SELECT 116,'nex_reward','number' UNION ALL
    SELECT 93,'campaign_id','id' UNION ALL
    SELECT 94,'campaign_id','id' UNION ALL
    SELECT 94,'reward_type','enum' UNION ALL
    SELECT 94,'reward_amount','number' UNION ALL
    SELECT 94,'badge_code','id' UNION ALL
    SELECT 51,'device_id','id' UNION ALL
    SELECT 51,'user_id','id' UNION ALL
    SELECT 51,'instance_no','id' UNION ALL
    SELECT 51,'before_status','enum' UNION ALL
    SELECT 51,'after_status','enum' UNION ALL
    SELECT 51,'mode','enum' UNION ALL
    SELECT 51,'operator','id' UNION ALL
    SELECT 51,'reason','string' UNION ALL
    SELECT 51,'ts','timestamp'
  ) p ON p.revision=s.current_revision;

INSERT IGNORE INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
SELECT s.owner_domain,s.event_name,e.producer,s.consumers,e.status,e.created_by,e.reason,0
  FROM tmp_a4_app_event_restore s
  JOIN (
    SELECT 116 revision,'AppLearningService' producer,'DONE' status,'migration:i6-i7-a4' created_by,
           'I7 server-authoritative course completion and NEX reward' reason UNION ALL
    SELECT 93,'AppGrowthEngagementService','REGISTERED','migration:h-user-events','H4 event domain user opt-in' UNION ALL
    SELECT 94,'AppGrowthEngagementService','REGISTERED','migration:h-user-events','H4 event domain reward claim' UNION ALL
    SELECT 51,'OpsDeviceService','REGISTERED','migration:e5-device-operations','E5 trusted activation contract consumed by H3'
  ) e ON e.revision=s.current_revision;

DROP TEMPORARY TABLE tmp_a4_app_event_restore;
COMMIT;
