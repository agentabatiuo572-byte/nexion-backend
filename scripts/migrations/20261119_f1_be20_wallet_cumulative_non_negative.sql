-- F1-BE-20: preserve the cumulative deposit non-negative predicate when the
-- wallet CHECK is rebuilt for negative USDT available balances (F1-BE-06).
-- USDT available remains intentionally excluded so F1 clawbacks can go negative;
-- NEX, pending withdrawal, lifetime earned and cumulative deposit stay non-negative.
--
-- Older startup-only databases may not have the D1 column yet. In that case the
-- existing CHECK is left untouched: the D1 column + its separate CHECK remain the
-- owning migration contract, and this guard must not make normal startup fail.
-- Where the column exists, replace the old CHECK in one atomic ALTER so a failed
-- ADD cannot leave the old protection dropped; existing negative values remain an
-- explicit atomic failure rather than being silently ignored.
SET @has_cumulative_deposit = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_wallet'
     AND COLUMN_NAME='cumulative_deposit_usdt'
);
SET @sql = IF(@has_cumulative_deposit > 0,
  IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
      WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_user_wallet_non_negative') > 0,
    'ALTER TABLE nx_user_wallet DROP CHECK chk_user_wallet_non_negative, ADD CONSTRAINT chk_user_wallet_non_negative CHECK (nex_available >= 0 AND pending_withdraw >= 0 AND lifetime_earned >= 0 AND cumulative_deposit_usdt >= 0)',
    'ALTER TABLE nx_user_wallet ADD CONSTRAINT chk_user_wallet_non_negative CHECK (nex_available >= 0 AND pending_withdraw >= 0 AND lifetime_earned >= 0 AND cumulative_deposit_usdt >= 0)'),
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
