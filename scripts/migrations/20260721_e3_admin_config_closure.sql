-- E3 admin closure: one canonical config vocabulary plus governed A4 change/action events.
-- MySQL 8, idempotent. Existing canonical operator values are preserved.
SET NAMES utf8mb4;

-- Preserve the three historical capacity values only when the canonical row does not yet exist.
INSERT INTO nx_compute_e3_config
  (config_key,config_value,value_type,updated_by,sort_order,created_at,updated_at,is_deleted)
SELECT mapping.target_key,legacy.config_value,'NUMBER','migration:e3-admin-closure',mapping.sort_order,NOW(),NOW(),0
  FROM nx_compute_e3_config legacy
  JOIN (
    SELECT 'degradeEarly' legacy_key,'capacityBand1DeltaPct' target_key,10 sort_order UNION ALL
    SELECT 'degradeMid','capacityBand2DeltaPct',20 UNION ALL
    SELECT 'degradeLate','capacityBand3DeltaPct',30 UNION ALL
    SELECT 'minEfficiency','capacityFloorPct',70
  ) mapping ON mapping.legacy_key=legacy.config_key
 WHERE legacy.is_deleted=0
ON DUPLICATE KEY UPDATE config_key=VALUES(config_key);

INSERT INTO nx_compute_e3_config
  (config_key,config_value,value_type,updated_by,sort_order,created_at,updated_at,is_deleted)
VALUES
  ('capacityBand1DeltaPct','-3','NUMBER','migration:e3-admin-closure',10,NOW(),NOW(),0),
  ('capacityBand2DeltaPct','-6','NUMBER','migration:e3-admin-closure',20,NOW(),NOW(),0),
  ('capacityBand3DeltaPct','-23.7','NUMBER','migration:e3-admin-closure',30,NOW(),NOW(),0),
  ('stageEarlyEnd','3','NUMBER','migration:e3-admin-closure',40,NOW(),NOW(),0),
  ('stageMidEnd','8','NUMBER','migration:e3-admin-closure',50,NOW(),NOW(),0),
  ('cycleMonths','12','NUMBER','migration:e3-admin-closure',60,NOW(),NOW(),0),
  ('capacityFloorPct','22','NUMBER','migration:e3-admin-closure',70,NOW(),NOW(),0),
  ('capacitySubsidyDays','30','NUMBER','migration:e3-admin-closure',75,NOW(),NOW(),0),
  ('taskLockS1','30','NUMBER','migration:e3-admin-closure',80,NOW(),NOW(),0),
  ('taskLockPro','150','NUMBER','migration:e3-admin-closure',90,NOW(),NOW(),0),
  ('taskLockRack','480','NUMBER','migration:e3-admin-closure',100,NOW(),NOW(),0),
  ('eligibility','L4+ 持有者','STRING','migration:e3-admin-closure',120,NOW(),NOW(),0),
  ('promoMult','1.5','NUMBER','migration:e3-admin-closure',140,NOW(),NOW(),0),
  ('promoCooldownDays','14','NUMBER','migration:e3-admin-closure',150,NOW(),NOW(),0),
  ('promoMaxPerSession','3','NUMBER','migration:e3-admin-closure',160,NOW(),NOW(),0),
  ('promoDelaySeconds','6','NUMBER','migration:e3-admin-closure',170,NOW(),NOW(),0),
  ('promoMinAgeDays','30','NUMBER','migration:e3-admin-closure',180,NOW(),NOW(),0),
  ('promoRoutes','/me/devices','STRING','migration:e3-admin-closure',190,NOW(),NOW(),0),
  ('inventorySoftMax','0','NUMBER','migration:e3-admin-closure',200,NOW(),NOW(),0),
  ('capacityApplyToPhone','false','BOOLEAN','migration:e3-admin-closure',210,NOW(),NOW(),0),
  ('capacityApplyToCloudShare','false','BOOLEAN','migration:e3-admin-closure',220,NOW(),NOW(),0),
  ('capacityApplyToPcGpu','false','BOOLEAN','migration:e3-admin-closure',230,NOW(),NOW(),0),
  ('capacityApplyToS1','true','BOOLEAN','migration:e3-admin-closure',240,NOW(),NOW(),0),
  ('capacityApplyToPro','true','BOOLEAN','migration:e3-admin-closure',250,NOW(),NOW(),0),
  ('capacityApplyToProV2','true','BOOLEAN','migration:e3-admin-closure',260,NOW(),NOW(),0),
  ('capacityApplyToRackP1','true','BOOLEAN','migration:e3-admin-closure',270,NOW(),NOW(),0),
  ('capacityApplyToRackP2','true','BOOLEAN','migration:e3-admin-closure',280,NOW(),NOW(),0),
  ('tradeinEnabled','true','BOOLEAN','migration:e3-admin-closure',300,NOW(),NOW(),0),
  ('tradeinLadderCut1','25','NUMBER','migration:e3-admin-closure',310,NOW(),NOW(),0),
  ('tradeinLadderCut2','50','NUMBER','migration:e3-admin-closure',320,NOW(),NOW(),0),
  ('tradeinLadderCut3','75','NUMBER','migration:e3-admin-closure',330,NOW(),NOW(),0),
  ('tradeinLadderCut4','100','NUMBER','migration:e3-admin-closure',340,NOW(),NOW(),0),
  ('tradeinLadderCredit1','75','NUMBER','migration:e3-admin-closure',350,NOW(),NOW(),0),
  ('tradeinLadderCredit2','60','NUMBER','migration:e3-admin-closure',360,NOW(),NOW(),0),
  ('tradeinLadderCredit3','45','NUMBER','migration:e3-admin-closure',370,NOW(),NOW(),0),
  ('tradeinLadderCredit4','30','NUMBER','migration:e3-admin-closure',380,NOW(),NOW(),0),
  ('tradeinLadderCredit5','15','NUMBER','migration:e3-admin-closure',390,NOW(),NOW(),0),
  ('tradeinRequireHigherPrice','true','BOOLEAN','migration:e3-admin-closure',400,NOW(),NOW(),0),
  ('tradeinMaxDevicesPerOrder','3','NUMBER','migration:e3-admin-closure',410,NOW(),NOW(),0),
  ('earlyAccessEnabled','false','BOOLEAN','migration:e3-admin-closure',420,NOW(),NOW(),0),
  ('earlyAccessLeadDays','30','NUMBER','migration:e3-admin-closure',430,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

UPDATE nx_compute_e3_config
   SET is_deleted=1,updated_by='migration:e3-admin-closure',updated_at=NOW()
 WHERE config_key IN ('degradeEarly','degradeMid','degradeLate','minEfficiency','salvagePct','minHoldingMonths')
   AND is_deleted=0;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.tradein_config_changed','admin','tradein','server','E3/A2/A4/B1/L4',1,'100%',47,
   'ACTIVE','migration:e3-admin-closure','E3 canonical configuration change',0),
  ('admin.tradein_action_executed','admin','tradein','server','E3/A2/A4/L4',1,'100%',48,
   'ACTIVE','migration:e3-admin-closure','E3 device trade-in operation result',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer=VALUES(producer),
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:e3-admin-closure',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('admin.tradein_config_changed','admin.tradein_action_executed')
   AND NOT (
     (s.event_name='admin.tradein_config_changed' AND p.property_name IN
       ('keys','before','after','effective_at','operator','reason'))
     OR
     (s.event_name='admin.tradein_action_executed' AND p.property_name IN
       ('operation','device_id','instance_no','before_status','after_status','trade_in_no','operator','reason'))
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'admin.tradein_config_changed' event_name,'keys' property_name,'json' property_type UNION ALL
    SELECT 'admin.tradein_config_changed','before','json' UNION ALL
    SELECT 'admin.tradein_config_changed','after','json' UNION ALL
    SELECT 'admin.tradein_config_changed','effective_at','timestamp' UNION ALL
    SELECT 'admin.tradein_config_changed','operator','id' UNION ALL
    SELECT 'admin.tradein_config_changed','reason','string' UNION ALL
    SELECT 'admin.tradein_action_executed','operation','enum' UNION ALL
    SELECT 'admin.tradein_action_executed','device_id','id' UNION ALL
    SELECT 'admin.tradein_action_executed','instance_no','id' UNION ALL
    SELECT 'admin.tradein_action_executed','before_status','enum' UNION ALL
    SELECT 'admin.tradein_action_executed','after_status','enum' UNION ALL
    SELECT 'admin.tradein_action_executed','trade_in_no','id' UNION ALL
    SELECT 'admin.tradein_action_executed','operator','id' UNION ALL
    SELECT 'admin.tradein_action_executed','reason','string'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('admin','admin.tradein_config_changed','OpsDeviceService','E3/A2/A4/B1/L4','REGISTERED',
   'migration:e3-admin-closure','E3 config change downstream contract',0),
  ('admin','admin.tradein_action_executed','OpsDeviceService','E3/A2/A4/L4','REGISTERED',
   'migration:e3-admin-closure','E3 action result downstream contract',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,48)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,48);
