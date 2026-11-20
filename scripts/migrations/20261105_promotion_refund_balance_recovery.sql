-- D06 recovery is bounded by the confirmed promotion reward and the current same-asset balance.
-- Keep the original watermark constraints on every other recovery mode.
SET @d06_recovery_sql=IF(EXISTS(
  SELECT 1 FROM information_schema.CHECK_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_earnings_recovery_evidence'),
  'ALTER TABLE nx_earnings_source_recovery DROP CHECK chk_earnings_recovery_evidence,
    ADD CONSTRAINT chk_earnings_recovery_evidence_d06 CHECK(
      (evidence_status=''LEGACY_UNPROVEN'' AND source_debit_baseline IS NULL AND recovered=0)
      OR (evidence_status=''TRACKED_CONSERVATIVE'' AND source_debit_baseline IS NOT NULL AND debit_counter_before>=source_debit_baseline)
      OR (evidence_status=''D06_AVAILABLE_BALANCE'' AND source_type=''PROMOTION_REWARD'' AND source_environment=''PRODUCTION''
          AND source_debit_baseline IS NOT NULL AND debit_counter_before>=source_debit_baseline))',
  'SELECT 1');
PREPARE d06_recovery_stmt FROM @d06_recovery_sql;
EXECUTE d06_recovery_stmt;
DEALLOCATE PREPARE d06_recovery_stmt;
