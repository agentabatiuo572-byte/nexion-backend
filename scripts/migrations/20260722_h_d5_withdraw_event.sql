-- H1 -> D5 execution closure: the user withdrawal transaction publishes the
-- complete server-authoritative payload. A4 must register that exact contract
-- so schema validation cannot turn a valid withdrawal into a rolled-back 422.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('withdraw.submitted','withdraw','monetization','AppWithdrawalService','A4/H1/D2/D4',
   1,'100%',108,'ACTIVE','migration:h-d5-withdraw-event',
   'H1 governed D5 withdrawal submission and exact fee settlement',0)
ON DUPLICATE KEY UPDATE
  owner_domain='withdraw',family_key='monetization',producer='AppWithdrawalService',
  consumers='A4/H1/D2/D4',is_server_authoritative=1,sampling_policy='100%',
  current_revision=108,status='ACTIVE',updated_by='migration:h-d5-withdraw-event',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='withdraw.submitted'
   AND p.property_name NOT IN (
     'withdrawal_id','amount_usdt','chain','penalty_fee_rate','gross_fee',
     'nex_burned','fee_waived','actual_fee','net_receive','cooldown_days','hold_until'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,108,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'withdrawal_id' property_name,'id' property_type UNION ALL
    SELECT 'amount_usdt','number' UNION ALL
    SELECT 'chain','enum' UNION ALL
    SELECT 'penalty_fee_rate','number' UNION ALL
    SELECT 'gross_fee','number' UNION ALL
    SELECT 'nex_burned','number' UNION ALL
    SELECT 'fee_waived','number' UNION ALL
    SELECT 'actual_fee','number' UNION ALL
    SELECT 'net_receive','number' UNION ALL
    SELECT 'cooldown_days','number' UNION ALL
    SELECT 'hold_until','timestamp'
  ) p
 WHERE s.event_name='withdraw.submitted'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=108,is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,108)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,108);
