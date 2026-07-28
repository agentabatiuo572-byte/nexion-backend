-- D4 -> A4 closure: register the authoritative immutable wallet-ledger event
-- before the transactional outbox is allowed to publish it.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('wallet.ledger_posted','wallet','money','MybatisTreasuryLedgerRepository',
   'D4/A4/App/B1/B2/L5',1,'100%',110,'ACTIVE',
   'migration:d4-wallet-ledger-event',
   'D4 authoritative immutable wallet ledger entry posted',0)
ON DUPLICATE KEY UPDATE
  owner_domain='wallet',family_key='money',producer='MybatisTreasuryLedgerRepository',
  consumers='D4/A4/App/B1/B2/L5',is_server_authoritative=1,sampling_policy='100%',
  current_revision=110,status='ACTIVE',updated_by='migration:d4-wallet-ledger-event',
  reason=VALUES(reason),is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='wallet.ledger_posted'
   AND p.property_name NOT IN (
     'user_id','biz_type','asset','direction','amount','balance_after','biz_no','status'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,110,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'user_id' property_name,'id' property_type UNION ALL
    SELECT 'biz_type','enum' UNION ALL
    SELECT 'asset','enum' UNION ALL
    SELECT 'direction','enum' UNION ALL
    SELECT 'amount','number' UNION ALL
    SELECT 'balance_after','number' UNION ALL
    SELECT 'biz_no','id' UNION ALL
    SELECT 'status','enum'
  ) p
 WHERE s.event_name='wallet.ledger_posted'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=110,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,110)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,110);
