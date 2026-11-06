-- H2 BE-40. Freeze the effective policy for open legacy claims before startup.
SET @h2_grace_column = IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_trial_claim' AND COLUMN_NAME='grace_days_snapshot'
), 'SELECT 1', 'ALTER TABLE nx_trial_claim ADD COLUMN grace_days_snapshot INT NULL');
PREPARE h2_grace_stmt FROM @h2_grace_column;
EXECUTE h2_grace_stmt;
DEALLOCATE PREPARE h2_grace_stmt;

SET @h2_extension_column = IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_trial_claim' AND COLUMN_NAME='extension_days_snapshot'
), 'SELECT 1', 'ALTER TABLE nx_trial_claim ADD COLUMN extension_days_snapshot INT NULL');
PREPARE h2_extension_stmt FROM @h2_extension_column;
EXECUTE h2_extension_stmt;
DEALLOCATE PREPARE h2_extension_stmt;

-- The H2 writer accepts whole days from 0 through 30 and stores plain decimals.
SELECT COALESCE(MAX(CASE WHEN policy_key='graceDays' THEN CAST(current_value AS UNSIGNED) END),7),
       COALESCE(MAX(CASE WHEN policy_key='extensionDays' THEN CAST(current_value AS UNSIGNED) END),3)
  INTO @h2_grace_days,@h2_extension_days
  FROM nx_growth_trial_policy
 WHERE is_deleted=0 AND policy_key IN ('graceDays','extensionDays')
   AND current_value REGEXP '^([0-9]|[12][0-9]|30)$';

-- A still-stored successful start receipt is evidence even if its replay lease has elapsed.
-- Claim number validation excludes results from a recycled key or an earlier claim.
UPDATE nx_trial_claim c
  LEFT JOIN nx_admin_idempotency_record i
    ON i.scope=CONCAT('APP:TRIAL_START:USER:',c.user_id)
   AND i.idempotency_key=TRIM(c.client_request_no)
   AND CAST(i.idempotency_key AS BINARY)=CAST(TRIM(c.client_request_no) AS BINARY)
   AND i.is_deleted=0 AND i.status='SUCCEEDED' AND i.response_json->>'$.code'='0'
   AND CAST(i.response_json->>'$.data.claimNo' AS BINARY)=CAST(c.claim_no AS BINARY)
   SET c.grace_days_snapshot=COALESCE(c.grace_days_snapshot,
         CASE WHEN i.response_json->>'$.data.config.graceDays' REGEXP '^([0-9]|[12][0-9]|30)$'
              THEN CAST(i.response_json->>'$.data.config.graceDays' AS UNSIGNED) END,@h2_grace_days),
       c.extension_days_snapshot=COALESCE(c.extension_days_snapshot,
         CASE WHEN i.response_json->>'$.data.config.extensionDays' REGEXP '^([0-9]|[12][0-9]|30)$'
              THEN CAST(i.response_json->>'$.data.config.extensionDays' AS UNSIGNED) END,@h2_extension_days),
       c.updated_at=c.updated_at
 WHERE c.is_deleted=0 AND UPPER(c.status) IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
   AND (c.grace_days_snapshot IS NULL OR c.extension_days_snapshot IS NULL);
