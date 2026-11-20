-- F1-BE-06: USDT available balance may go negative when a used voucher is clawed back.
-- Drop the old CHECK that required usdt_available >= 0 and re-add it without that
-- predicate; NEX, pending withdrawal and lifetime earnings stay non-negative.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_user_wallet_non_negative') > 0,
  'ALTER TABLE nx_user_wallet DROP CHECK chk_user_wallet_non_negative',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_user_wallet_non_negative') = 0,
  'ALTER TABLE nx_user_wallet ADD CONSTRAINT chk_user_wallet_non_negative CHECK (nex_available >= 0 AND pending_withdraw >= 0 AND lifetime_earned >= 0)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;