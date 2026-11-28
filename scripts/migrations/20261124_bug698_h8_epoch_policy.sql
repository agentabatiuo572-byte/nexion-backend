-- BUG-698: the original H8 epoch seed was a placeholder, not a real eligibility start.
-- Close that configuration at an actual boundary. Normal H8 approval controls any future enable.
START TRANSACTION;
SET @bug698_h8_closed_at = DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ');

UPDATE nx_config_item effective_at
JOIN nx_config_item enabled
  ON enabled.config_key = 'K.rewards.referral.enabled' AND enabled.status = 1 AND enabled.is_deleted = 0
JOIN nx_config_item policy_version
  ON policy_version.config_key = 'K.rewards.referral.version' AND policy_version.status = 1 AND policy_version.is_deleted = 0
SET effective_at.config_value = @bug698_h8_closed_at,
    effective_at.remark = 'BUG-698: legacy epoch policy closed without creating an eligible cohort',
    enabled.config_value = 'false',
    policy_version.config_value = CAST(policy_version.config_value AS UNSIGNED) + 1
WHERE effective_at.config_key = 'K.rewards.referral.effectiveAt'
  AND effective_at.status = 1 AND effective_at.is_deleted = 0
  AND REGEXP_LIKE(effective_at.config_value, '^1970-01-01T00:00:00([.]0{1,9})?Z$', 'c')
  AND CASE WHEN REGEXP_LIKE(TRIM(policy_version.config_value), '^[0-9]{1,19}$', 'c')
           THEN CAST(policy_version.config_value AS UNSIGNED) BETWEEN 1 AND 9223372036854775805
           ELSE FALSE END;

COMMIT;
