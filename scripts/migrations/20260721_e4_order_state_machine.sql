-- E4 canonical order lifecycle closure.
-- nx_order remains the single source of truth; the legacy nx_admin_device_order table is intentionally not read.

CREATE TABLE IF NOT EXISTS nx_order_state_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(96) NOT NULL,
  from_state VARCHAR(32) NOT NULL,
  to_state VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  operator VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_order_state_history_idempotency (order_no,idempotency_key),
  KEY idx_order_state_history_order_time (order_no,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_order_state_history
  (order_no,from_state,to_state,reason,operator,idempotency_key,created_at)
SELECT o.order_no,
       'placed',
       CASE
         WHEN UPPER(COALESCE(o.order_status,''))='REFUNDED'
           OR UPPER(COALESCE(o.payment_status,''))='REFUNDED' THEN 'refunded'
         WHEN UPPER(COALESCE(o.order_status,'')) IN ('CHARGEBACK','DISPUTED')
           OR UPPER(COALESCE(o.payment_status,'')) IN ('CHARGEBACK','DISPUTED') THEN 'chargeback'
         WHEN UPPER(COALESCE(o.order_status,''))='COMPLETED'
           OR UPPER(COALESCE(o.activation_status,''))='ACTIVATED' THEN 'activated'
         WHEN UPPER(COALESCE(o.order_status,'')) IN ('PROVISIONING','ALLOCATING')
           OR UPPER(COALESCE(o.activation_status,'')) IN ('PROVISIONING','ALLOCATING') THEN 'provisioning'
         WHEN UPPER(COALESCE(o.payment_status,'')) IN ('PAID','CONFIRMED','SUCCESS')
           OR o.paid_at IS NOT NULL THEN 'paid'
         ELSE 'placed'
       END,
       'E4 canonical history baseline',
       'system:migration',
       CONCAT('E4-BASELINE-',o.id),
       o.created_at
  FROM nx_order o
 WHERE o.is_deleted=0
ON DUPLICATE KEY UPDATE order_no=VALUES(order_no);

-- E4 refund publishes the business event and the operator event through the A4 governed outbox.
INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('order.refunded','order','checkout','server','E4/D1/D4/A4/L3',1,'100%',49,
   'ACTIVE','migration:e4-order-state-machine','E4 canonical refund business event',0),
  ('admin.order_refunded','admin','checkout','server','E4/D1/D4/A2/A4/L3',1,'100%',50,
   'ACTIVE','migration:e4-order-state-machine','E4 refund operator event',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer=VALUES(producer),
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:e4-order-state-machine',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('order.refunded','admin.order_refunded')
   AND p.property_name NOT IN
     ('order_id','amount','refund_channel','cumulative_deposit_adjusted','operator','reason',
      'from_state','to_state','wallet_before','wallet_after','ledger_biz_no','bill_no');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'order_id' property_name,'id' property_type UNION ALL
    SELECT 'amount','number' UNION ALL
    SELECT 'refund_channel','enum' UNION ALL
    SELECT 'cumulative_deposit_adjusted','number' UNION ALL
    SELECT 'operator','id' UNION ALL
    SELECT 'reason','string' UNION ALL
    SELECT 'from_state','enum' UNION ALL
    SELECT 'to_state','enum' UNION ALL
    SELECT 'wallet_before','number' UNION ALL
    SELECT 'wallet_after','number' UNION ALL
    SELECT 'ledger_biz_no','id' UNION ALL
    SELECT 'bill_no','id'
  ) p
 WHERE s.event_name IN ('order.refunded','admin.order_refunded')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('order','order.refunded','OpsDeviceService','E4/D1/D4/A4/L3','REGISTERED',
   'migration:e4-order-state-machine','E4 canonical refund downstream contract',0),
  ('admin','admin.order_refunded','OpsDeviceService','E4/D1/D4/A2/A4/L3','REGISTERED',
   'migration:e4-order-state-machine','E4 refund operation downstream contract',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,50)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,50);
