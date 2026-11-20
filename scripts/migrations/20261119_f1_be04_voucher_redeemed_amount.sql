-- F1-BE-04: keep the immutable amount actually redeemed by a used voucher so a later
-- clawback never reads the current (possibly changed) voucher template face value.
-- Legacy USED grants keep NULL; Java treats NULL as 0 and skips the wallet debit
-- (hold for review instead of guessing an amount).

SET @sql = IF(EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema=DATABASE() AND table_name='nx_growth_voucher_grant')
              AND (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_voucher_grant'
                     AND COLUMN_NAME='redeemed_amount_usdt') = 0,
  'ALTER TABLE nx_growth_voucher_grant ADD COLUMN redeemed_amount_usdt DECIMAL(18,6) NULL AFTER used_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;