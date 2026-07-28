-- D5 withdrawal execution closure:
-- 1. Persist the immutable network-fee inputs and component on every D2 order.
-- 2. Register the exact current withdraw.submitted A4 payload at revision 275.

SET @schema_name = DATABASE();

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='nx_withdrawal_order'
      AND column_name='d2_network_fee_rate') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_network_fee_rate DECIMAL(10,6) NULL AFTER d2_hold_until',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='nx_withdrawal_order'
      AND column_name='d2_network_fee_min') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_network_fee_min DECIMAL(18,6) NULL AFTER d2_network_fee_rate',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='nx_withdrawal_order'
      AND column_name='d2_network_fee_max') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_network_fee_max DECIMAL(18,6) NULL AFTER d2_network_fee_min',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=@schema_name AND table_name='nx_withdrawal_order'
      AND column_name='d2_network_fee') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_network_fee DECIMAL(18,6) NULL AFTER d2_network_fee_max',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Historic orders did not snapshot this component. Zero records "not charged/unknown"
-- without fabricating the current policy into old transactions.
UPDATE nx_withdrawal_order
   SET d2_network_fee_rate=COALESCE(d2_network_fee_rate,0),
       d2_network_fee_min=COALESCE(d2_network_fee_min,0),
       d2_network_fee_max=COALESCE(d2_network_fee_max,0),
       d2_network_fee=COALESCE(d2_network_fee,0)
 WHERE d2_network_fee_rate IS NULL OR d2_network_fee_min IS NULL
    OR d2_network_fee_max IS NULL OR d2_network_fee IS NULL;

ALTER TABLE nx_withdrawal_order
  MODIFY COLUMN d2_network_fee_rate DECIMAL(10,6) NOT NULL DEFAULT 0,
  MODIFY COLUMN d2_network_fee_min DECIMAL(18,6) NOT NULL DEFAULT 0,
  MODIFY COLUMN d2_network_fee_max DECIMAL(18,6) NOT NULL DEFAULT 0,
  MODIFY COLUMN d2_network_fee DECIMAL(18,6) NOT NULL DEFAULT 0;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('withdraw.submitted','withdraw','monetization','AppWithdrawalService','A4/H1/D2/D4',
   1,'100%',276,'ACTIVE','migration:d5-withdrawal-execution-closure',
   'Exact server-authoritative D2/D4/D5/H1 withdrawal and ledger-component snapshot',0)
ON DUPLICATE KEY UPDATE
  owner_domain='withdraw',family_key='monetization',producer='AppWithdrawalService',
  consumers='A4/H1/D2/D4',is_server_authoritative=1,sampling_policy='100%',
  current_revision=276,status='ACTIVE',
  updated_by='migration:d5-withdrawal-execution-closure',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name='withdraw.submitted'
   AND p.property_name NOT IN (
     'withdrawal_id','amount_usdt','chain',
     'network_fee_rate','network_fee_min','network_fee_max','network_fee',
     'penalty_fee_rate','penalty_fee','gross_fee','nex_burned',
     'fee_waived','penalty_fee_waived','network_fee_waived',
     'actual_penalty_fee','actual_network_fee',
     'actual_fee','net_receive','cooldown_days','hold_until',
     'risk_route','k3_risk_route','risk_rule_id','k4_priority',
     'k4_risk_score','k4_model_version','k4_as_of','k5_ticket_id'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,276,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'withdrawal_id' property_name,'id' property_type,1 required_field UNION ALL
    SELECT 'amount_usdt','number',1 UNION ALL
    SELECT 'chain','enum',1 UNION ALL
    SELECT 'network_fee_rate','number',1 UNION ALL
    SELECT 'network_fee_min','number',1 UNION ALL
    SELECT 'network_fee_max','number',1 UNION ALL
    SELECT 'network_fee','number',1 UNION ALL
    SELECT 'penalty_fee_rate','number',1 UNION ALL
    SELECT 'penalty_fee','number',1 UNION ALL
    SELECT 'gross_fee','number',1 UNION ALL
    SELECT 'nex_burned','number',1 UNION ALL
    SELECT 'fee_waived','number',1 UNION ALL
    SELECT 'penalty_fee_waived','number',1 UNION ALL
    SELECT 'network_fee_waived','number',1 UNION ALL
    SELECT 'actual_penalty_fee','number',1 UNION ALL
    SELECT 'actual_network_fee','number',1 UNION ALL
    SELECT 'actual_fee','number',1 UNION ALL
    SELECT 'net_receive','number',1 UNION ALL
    SELECT 'cooldown_days','number',1 UNION ALL
    SELECT 'hold_until','timestamp',1 UNION ALL
    SELECT 'risk_route','enum',1 UNION ALL
    SELECT 'k3_risk_route','enum',1 UNION ALL
    SELECT 'risk_rule_id','id',0 UNION ALL
    SELECT 'k4_priority','enum',1 UNION ALL
    SELECT 'k4_risk_score','number',1 UNION ALL
    SELECT 'k4_model_version','string',1 UNION ALL
    SELECT 'k4_as_of','timestamp',1 UNION ALL
    SELECT 'k5_ticket_id','id',0
  ) p
 WHERE s.event_name='withdraw.submitted'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),
  pii=0,
  required_field=VALUES(required_field),
  registry_revision=276,
  is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,276)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,276);
