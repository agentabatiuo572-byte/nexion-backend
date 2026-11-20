-- Publish the jobs display base only for untouched H9 launch settings.
UPDATE nx_config_item c
JOIN nx_config_item v ON v.config_key = 'growth.public_stats.version'
  AND v.status = 1 AND v.is_deleted = 0
LEFT JOIN nx_config_item b ON b.config_key = 'growth.home_display.batch'
  AND b.status = 1 AND b.is_deleted = 0 AND JSON_VALID(b.config_value)
  AND JSON_EXTRACT(b.config_value, '$.enabled') = CAST('false' AS JSON)
SET c.config_value = JSON_SET(c.config_value, '$.activeJobsBase', 560),
    c.updated_at = NOW(),
    v.config_value = CAST(CAST(v.config_value AS UNSIGNED) + 1 AS CHAR),
    v.updated_at = NOW(),
    b.config_value = JSON_SET(b.config_value, '$.activeJobs', JSON_ARRAY(560),
      '$.sequence', CAST(JSON_UNQUOTE(JSON_EXTRACT(b.config_value, '$.sequence')) AS UNSIGNED) + 1),
    b.updated_at = NOW()
WHERE c.config_key = 'growth.public_stats.values' AND c.status = 1 AND c.is_deleted = 0
  AND JSON_VALID(c.config_value)
  AND COALESCE(JSON_TYPE(JSON_EXTRACT(c.config_value, '$.activeJobsBase')), 'NULL') = 'NULL'
  AND JSON_EXTRACT(c.config_value, '$.homeDisplayEnabled') = CAST('false' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeOnlineCurrent') = CAST('800' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeOnlineStepMin') = CAST('0' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeOnlineStepMax') = CAST('0' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homePaidCurrent') = CAST('3000' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homePaidStepMin') = CAST('0' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homePaidStepMax') = CAST('0' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersBase') = CAST('2000' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersTickIntervalSec') = CAST('300' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersTickStepMin') = CAST('0' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersTickStepMax') = CAST('2' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeDisplayAnchorAt') = JSON_EXTRACT(c.config_value, '$.effectiveAt')
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersAnchorAt') = JSON_EXTRACT(c.config_value, '$.effectiveAt')
  AND NOT EXISTS (
      SELECT 1 FROM nx_audit_log a
      WHERE a.is_deleted = 0 AND a.action = 'GROWTH_H9_PUBLIC_STATS_UPDATED' AND a.resource_id = 'H9'
  )
  AND NOT EXISTS (
      SELECT 1 FROM nx_audit_operation_ticket t
      WHERE t.is_deleted = 0 AND t.status IN ('pending', 'approved')
        AND (t.object_text IN ('growth.public_stats.values', 'growth.public_stats.version')
             OR (t.source_domain = 'H'
                 AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(t.command_json), t.command_json, '{}'), '$.op'))
                     = 'h9_public_stats_update'))
  );
