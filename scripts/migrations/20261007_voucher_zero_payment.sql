-- Canonical checkout already settles a fully redeemed voucher as VOUCHER/USDT/0.
-- Keep every other payment positive and reject all negative amounts. Replace the
-- old constraint atomically; no data, payment, voucher or approval is fabricated.
SET @voucher_zero_existing_check = (
  SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_payment_record_positive_amount'
);
SET @voucher_zero_payment_sql = IF(@voucher_zero_existing_check IS NULL,
  'ALTER TABLE nx_payment_record ADD CONSTRAINT chk_payment_record_positive_amount CHECK (amount_usdt > 0 OR (amount_usdt = 0 AND provider = ''VOUCHER'' AND currency = ''USDT''))',
  IF(LOCATE('VOUCHER',UPPER(@voucher_zero_existing_check))=0,
    'ALTER TABLE nx_payment_record DROP CHECK chk_payment_record_positive_amount, ADD CONSTRAINT chk_payment_record_positive_amount CHECK (amount_usdt > 0 OR (amount_usdt = 0 AND provider = ''VOUCHER'' AND currency = ''USDT''))',
    'SELECT 1'));
PREPARE voucher_zero_payment_stmt FROM @voucher_zero_payment_sql;
EXECUTE voucher_zero_payment_stmt;
DEALLOCATE PREPARE voucher_zero_payment_stmt;
