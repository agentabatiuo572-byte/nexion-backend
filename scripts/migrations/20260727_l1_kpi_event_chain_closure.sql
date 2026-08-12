-- L1 KPI canonical event-chain closure.
-- Registers every locked §2.4.6 event that was still absent from A4 and
-- extends commission/checkout payloads for the real F2 consumer chain.
-- The controlled startup runner selects the target database.  Do not pin this
-- migration to the production schema: acceptance uses an isolated database.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('device.first_yield_received','device','acquisition','DeviceEarningRuntime',
   'A4/B3/L1/L2/L4',1,'100%',285,'ACTIVE','migration:l1-kpi',
   'L1 Day0 authoritative first-yield latency fact',0),
  ('app.dau','app','engagement','AppSessionRuntime',
   'A4/B3/L1/L2/L4',1,'100%',285,'ACTIVE','migration:l1-kpi',
   'L1 Day7 server-deduplicated daily active fact',0),
  ('store.viewed','store','conversion','AppBehaviorRuntime',
   'A4/B3/L1/L2/L4',0,'100%',285,'ACTIVE','migration:l1-kpi',
   'L1 locked client store-view interaction fact',0),
  ('device.purchase_completed','device','conversion','CheckoutRuntime',
   'A4/B3/L1/L4',1,'100%',285,'ACTIVE','migration:l1-kpi',
   'L1 device-holder acquisition fact',0),
  ('referral.invite_sent','referral','engagement','ReferralRuntime',
   'A4/L1/L4',1,'100%',285,'ACTIVE','migration:l1-kpi',
   'L1 device-holder invitation fact',0),
  ('nova.push_sent','nova','engagement','NovaRuntime',
   'A4/I2/L1',1,'100%',285,'ACTIVE','migration:l1-kpi',
   'L1 Nova delivered-message denominator fact',0),
  ('nova.push_clicked','nova','engagement','AppNovaRuntime',
   'A4/I2/L1',0,'100%',285,'ACTIVE','migration:l1-kpi',
   'L1 locked client Nova click fact',0),
  ('referral.bound','referral','acquisition','AppUserRegistrationService',
   'A4/F1/F2/L1/L4',1,'100%',285,'ACTIVE','migration:l1-kpi',
   'Canonical direct-referral relationship created with registration',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),
  producer=VALUES(producer),consumers=VALUES(consumers),
  is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy='100%',current_revision=285,status='ACTIVE',
  updated_by='migration:l1-kpi',reason=VALUES(reason),is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,285,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'device.first_yield_received' event_name,'latency_sec' property_name,'number' property_type,1 required_field
    UNION ALL SELECT 'device.purchase_completed','order_id','id',1
    UNION ALL SELECT 'referral.invite_sent','channel','enum',0
    UNION ALL SELECT 'nova.push_sent','notification_id','id',0
    UNION ALL SELECT 'nova.push_clicked','notification_id','id',0
    UNION ALL SELECT 'referral.bound','sponsor_user_id','id',1
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=285,is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_registry
   SET producer='F2/F3/F4/F5 settlement',
       consumers='A4/D4/B1/B2/F4/F5/L1/L4',
       current_revision=285,status='ACTIVE',is_deleted=0,
       updated_by='migration:l1-kpi',
       reason='Commission recipient fact with optional network attribution for L1 KPI #7',
       updated_at=NOW()
 WHERE event_name='commission.paid';

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,0,285,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'source_user_id' property_name,'id' property_type
    UNION ALL SELECT 'layer','number'
    UNION ALL SELECT 'order_no','id'
  ) p
 WHERE s.event_name='commission.paid'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=0,
  registry_revision=285,is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_registry
   SET consumers='A4/F1/F2/B3/L1/L2/L4',
       current_revision=285,status='ACTIVE',is_deleted=0,
       updated_by='migration:l1-kpi',updated_at=NOW()
 WHERE event_name='checkout.completed';

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,0,285,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'order_no' property_name,'id' property_type
    UNION ALL SELECT 'order_subtotal_usdt','number'
    UNION ALL SELECT 'amount_usdt','number'
  ) p
 WHERE s.event_name='checkout.completed'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=0,
  registry_revision=285,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,285)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,285);
