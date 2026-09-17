-- Keep the canonical D2/L3 events. A bank payout has provider evidence, not a chain transaction.
-- Conditional proof requirements are enforced by WithdrawalSettlementEventContract.
SET NAMES utf8mb4;

UPDATE nx_event_schema_registry
   SET current_revision=GREATEST(current_revision,20260917),
       updated_by='migration:hdpay-settlement-event',updated_at=NOW()
 WHERE event_name IN ('withdraw.confirmed','withdraw.refunded') AND status='ACTIVE' AND is_deleted=0;

UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.registry_revision=s.current_revision,
       p.required_field=CASE WHEN (s.event_name='withdraw.confirmed' AND p.property_name='chain_tx_hash')
                              OR (s.event_name='withdraw.refunded' AND p.property_name='risk_score')
                            THEN 0 ELSE p.required_field END,
       p.updated_at=NOW()
 WHERE s.event_name IN ('withdraw.confirmed','withdraw.refunded') AND s.status='ACTIVE'
   AND s.is_deleted=0 AND p.is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,0,s.current_revision,0
  FROM nx_event_schema_registry s JOIN (
    SELECT 'rail' property_name,'string' property_type UNION ALL
    SELECT 'provider','string' UNION ALL
    SELECT 'provider_order_id','id' UNION ALL
    SELECT 'amount_vnd','number'
  ) p
 WHERE s.event_name IN ('withdraw.confirmed','withdraw.refunded') AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=0,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT id,'risk_score_status','string',0,0,current_revision,0 FROM nx_event_schema_registry
 WHERE event_name='withdraw.refunded' AND status='ACTIVE' AND is_deleted=0
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=0,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision(id,current_revision) VALUES(1,20260917)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,20260917);
