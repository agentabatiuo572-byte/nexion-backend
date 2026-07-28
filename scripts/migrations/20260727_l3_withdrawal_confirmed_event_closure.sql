-- L3/D2/A4 closure: one controlled SENT -> CONFIRMED transition and one
-- server-authoritative occurrence-time fact for the financial redemption report.

-- A chain proof may close at most one withdrawal. Multiple NULL values remain valid.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='nx_withdrawal_order'
      AND index_name IN ('uk_withdrawal_chain_tx_hash','uk_withdrawal_chain_tx')) = 0,
  'ALTER TABLE nx_withdrawal_order ADD UNIQUE KEY uk_withdrawal_chain_tx_hash (chain_tx_hash)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('withdraw.confirmed','withdraw','money','OpsFinanceService','A4/D2/L3',
   1,'100%',288,'ACTIVE','migration:l3-withdrawal-confirmed',
   'Unique server-controlled chain confirmation used by occurrence-time L3 redemption aggregation',0)
ON DUPLICATE KEY UPDATE
  owner_domain='withdraw',family_key='money',producer='OpsFinanceService',
  consumers='A4/D2/L3',is_server_authoritative=1,sampling_policy='100%',
  current_revision=288,status='ACTIVE',
  updated_by='migration:l3-withdrawal-confirmed',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='withdraw.confirmed'
   AND p.property_name NOT IN (
     'withdrawal_id','amount','currency','state','chain_tx_hash',
     'confirmed_at','operator','reason'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,288,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'withdrawal_id' property_name,'id' property_type UNION ALL
    SELECT 'amount','number' UNION ALL
    SELECT 'currency','string' UNION ALL
    SELECT 'state','string' UNION ALL
    SELECT 'chain_tx_hash','id' UNION ALL
    SELECT 'confirmed_at','timestamp' UNION ALL
    SELECT 'operator','string' UNION ALL
    SELECT 'reason','string'
  ) p
 WHERE s.event_name='withdraw.confirmed'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=288,is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,288)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,288);
