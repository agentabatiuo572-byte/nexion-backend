-- Append-only: no channel enablement or fee/configuration changes.
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_purchase_payment_intent' AND COLUMN_NAME='provider_expires_at')=0,
 'ALTER TABLE nx_purchase_payment_intent ADD COLUMN provider_expires_at DATETIME(6) NULL,ADD COLUMN paid_at DATETIME(6) NULL,ADD COLUMN reconciliation_status VARCHAR(24) NOT NULL DEFAULT ''PENDING''','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
CREATE TABLE IF NOT EXISTS nx_purchase_payment_result (
 notification_key VARCHAR(128) NOT NULL,intent_no VARCHAR(48) NOT NULL,outcome VARCHAR(24) NOT NULL,user_id BIGINT NOT NULL,order_no VARCHAR(96) NOT NULL DEFAULT '',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(notification_key,outcome),KEY idx_purchase_result_created(created_at)
) ENGINE=InnoDB;
-- Existing unfulfilled order intents used quote+15m. Align only their deadline, never historical receipts or fees.
UPDATE nx_purchase_payment_intent p JOIN nx_order o ON o.order_no=JSON_UNQUOTE(JSON_EXTRACT(p.payload_json,'$.orderNo'))
 LEFT JOIN nx_promotion_order_receipt r ON r.order_no=o.order_no
 SET p.expires_at=LEAST(TIMESTAMPADD(MINUTE,30,o.created_at),COALESCE(r.pay_by,TIMESTAMPADD(MINUTE,30,o.created_at)))
 WHERE p.kind='ORDER_PAY' AND p.receipt_json IS NULL AND p.confirmed_at IS NULL
   AND o.payment_status='PENDING' AND o.order_status='PENDING_PAYMENT';
