-- Payout shares the HDPay merchant configuration. Retain old switch values for rollback,
-- but application code no longer reads them. Capture the actual submission IP per order.
-- No fabricated IP backfill: legacy orders without request evidence cannot be dispatched.
SET @has_payout_ip = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_payout' AND column_name='client_ip');
SET @add_payout_ip = IF(@has_payout_ip=0,
  'ALTER TABLE nx_hdpay_payout ADD COLUMN client_ip VARCHAR(45) NULL COMMENT ''Original authenticated submission IP''',
  'SELECT 1');
PREPARE payout_ip_stmt FROM @add_payout_ip;
EXECUTE payout_ip_stmt;
DEALLOCATE PREPARE payout_ip_stmt;
