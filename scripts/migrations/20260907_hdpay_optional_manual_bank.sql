-- Hosted PSP intents do not have a merchant-owned receiving bank account.
-- Existing intents retain their bank assignment and all financial values.
ALTER TABLE nx_vietqr_intent MODIFY COLUMN bank_account_id BIGINT NULL;

-- Persist origin: historic HDPay intents were allocated a manual bank account,
-- so bank_account_id cannot identify their rail after a configuration change.
SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_vietqr_intent'
    AND column_name='payment_rail')=0,
  'ALTER TABLE nx_vietqr_intent ADD COLUMN payment_rail ENUM(''MANUAL'',''HDPAY'') NOT NULL DEFAULT ''MANUAL'' AFTER bank_account_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- A manual-only installation has no provider table. A durable provider row,
-- written before the network call, proves a historic intent entered HDPay.
SET @sql=IF((SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_payin_order')=1,
  'UPDATE nx_vietqr_intent i JOIN nx_hdpay_payin_order h ON h.merchant_order_id=i.intent_no SET i.payment_rail=''HDPAY'' WHERE i.payment_rail=''MANUAL''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
