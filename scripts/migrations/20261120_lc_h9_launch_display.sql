-- Promote the untouched H9 installation values together. Preserve operator settings and A2 tickets.
SET @lc_h9_now_ms = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

UPDATE nx_config_item c
JOIN nx_config_item v ON v.config_key = 'growth.public_stats.version'
  AND v.status = 1 AND v.is_deleted = 0
LEFT JOIN nx_config_item b ON b.config_key = 'growth.home_display.batch'
  AND b.status = 1 AND b.is_deleted = 0 AND JSON_VALID(b.config_value)
SET c.config_value = JSON_SET(c.config_value,
      '$.homeOnlineCurrent', 800, '$.homeOnlineStepMin', 0, '$.homeOnlineStepMax', 0,
      '$.homePaidCurrent', 3000, '$.homePaidStepMin', 0, '$.homePaidStepMax', 0,
      '$.homeDisplayAnchorAt', @lc_h9_now_ms,
      '$.registeredUsersBase', 2000, '$.registeredUsersTickIntervalSec', 300,
      '$.registeredUsersTickStepMin', 0, '$.registeredUsersTickStepMax', 2,
      '$.registeredUsersAnchorAt', @lc_h9_now_ms, '$.effectiveAt', @lc_h9_now_ms),
    c.updated_at = NOW(),
    v.config_value = CAST(CAST(v.config_value AS UNSIGNED) + 1 AS CHAR),
    v.updated_at = NOW(),
    -- A disabled batch never extends. Replace it if the old defaults already produced one.
    b.config_value = JSON_OBJECT(
      'sequence', CAST(JSON_UNQUOTE(JSON_EXTRACT(b.config_value, '$.sequence')) AS UNSIGNED) + 1,
      'enabled', CAST('false' AS JSON), 'startAt', @lc_h9_now_ms,
      'online', JSON_ARRAY(800), 'paidCents', JSON_ARRAY(300000)),
    b.updated_at = NOW()
WHERE c.config_key = 'growth.public_stats.values' AND c.status = 1 AND c.is_deleted = 0
  AND JSON_VALID(c.config_value)
  AND JSON_EXTRACT(c.config_value, '$.homeDisplayEnabled') = CAST('false' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeOnlineCurrent') = CAST('28432' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeOnlineStepMin') = CAST('1' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeOnlineStepMax') = CAST('3' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homePaidCurrent') = CAST('130532' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homePaidStepMin') = CAST('1.10' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homePaidStepMax') = CAST('5.50' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersBase') = CAST('1420000' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersTickIntervalSec') = CAST('60' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersTickStepMin') = CAST('3' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersTickStepMax') = CAST('10' AS JSON)
  AND NOT EXISTS (
      SELECT 1 FROM nx_audit_operation_ticket t
      WHERE t.is_deleted = 0 AND t.status IN ('pending', 'approved')
        AND (t.object_text IN ('growth.public_stats.values', 'growth.public_stats.version')
             OR (t.source_domain = 'H'
                 AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(t.command_json), t.command_json, '{}'), '$.op'))
                     = 'h9_public_stats_update'))
  );
