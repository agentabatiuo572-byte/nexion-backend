-- Preserve the effective legacy rate, including 1 = 100%. The H2 PATCH
-- writer stores a canonical plain decimal. Review values outside that format
-- before switching pricing to percentage points.
START TRANSACTION;
INSERT INTO nx_schema_migration_state (migration_key, phase)
VALUES ('20261106_h2_discount_rate_percent', 'PENDING')
ON DUPLICATE KEY UPDATE migration_key = migration_key;

SET @h2_discount_rate_check = IF(EXISTS (
  SELECT 1
    FROM nx_growth_trial_policy p
    JOIN nx_schema_migration_state m ON m.migration_key = '20261106_h2_discount_rate_percent'
   WHERE m.phase = 'PENDING' AND BINARY p.policy_key = 'discountRate'
     AND p.current_value NOT REGEXP '^(100|[1-9]?[0-9]([.][0-9]*[1-9])?)$'
), 'SELECT H2_DISCOUNT_RATE_LEGACY_REVIEW_REQUIRED', 'SELECT 1');
PREPARE h2_discount_rate_check FROM @h2_discount_rate_check;
EXECUTE h2_discount_rate_check;
DEALLOCATE PREPARE h2_discount_rate_check;

-- Shift decimal text exactly, without rounding values accepted by the writer
-- to a fixed SQL scale. The marker commits with the value, so a restart never
-- rescales a value that is still below 1 or a later write in the new unit.
UPDATE nx_growth_trial_policy p
JOIN nx_schema_migration_state m ON m.migration_key = '20261106_h2_discount_rate_percent'
SET p.current_value = CASE
    WHEN p.current_value = '1' THEN '100'
    ELSE CONCAT(CAST(RPAD(SUBSTRING(p.current_value, 3, 2), 2, '0') AS UNSIGNED),
                IF(CHAR_LENGTH(p.current_value) > 4, CONCAT('.', SUBSTRING(p.current_value, 5)), ''))
END
WHERE m.phase = 'PENDING' AND BINARY p.policy_key = 'discountRate'
  AND (p.current_value = '1' OR p.current_value LIKE '0.%');

UPDATE nx_schema_migration_state SET phase = 'FINAL'
WHERE migration_key = '20261106_h2_discount_rate_percent' AND phase = 'PENDING';
COMMIT;
