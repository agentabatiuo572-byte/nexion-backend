-- Materialize the paused H9 launch batch when the installation has no durable batch yet.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT 'growth.home_display.batch',
       JSON_OBJECT('sequence', 1, 'enabled', CAST('false' AS JSON),
         'startAt', JSON_EXTRACT(c.config_value, '$.homeDisplayAnchorAt'),
         'online', JSON_ARRAY(800), 'paidCents', JSON_ARRAY(300000), 'activeJobs', JSON_ARRAY(560)),
       'JSON', 'growth', 'ADMIN', 'H9 shared random display batch', 1, 0
FROM nx_config_item c
WHERE c.config_key = 'growth.public_stats.values' AND c.status = 1 AND c.is_deleted = 0
  AND JSON_VALID(c.config_value)
  AND JSON_EXTRACT(c.config_value, '$.homeDisplayEnabled') = CAST('false' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeOnlineCurrent') = CAST('800' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homePaidCurrent') = CAST('3000' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.activeJobsBase') = CAST('560' AS JSON)
  AND JSON_EXTRACT(c.config_value, '$.homeDisplayAnchorAt') = JSON_EXTRACT(c.config_value, '$.effectiveAt')
  AND JSON_EXTRACT(c.config_value, '$.registeredUsersAnchorAt') = JSON_EXTRACT(c.config_value, '$.effectiveAt')
  AND NOT EXISTS (
      SELECT 1 FROM nx_audit_log a
      WHERE a.is_deleted = 0 AND a.action = 'GROWTH_H9_PUBLIC_STATS_UPDATED' AND a.resource_id = 'H9'
  )
  AND NOT EXISTS (
      SELECT 1 FROM nx_config_item b WHERE b.config_key = 'growth.home_display.batch'
  );
