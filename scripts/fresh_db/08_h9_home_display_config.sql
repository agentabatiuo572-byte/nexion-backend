-- H9 display installation values from manual_migrations/20261025_team_v8_home_display.sql.
-- First installation only: an existing enabled flag preserves the whole configuration.
UPDATE nx_config_item v
JOIN nx_config_item c ON c.config_key = 'growth.public_stats.values'
  AND c.status = 1 AND c.is_deleted = 0
SET c.config_value = JSON_INSERT(c.config_value,
      '$.homeOnlineCurrent', 28432, '$.homeOnlineStepMin', 1, '$.homeOnlineStepMax', 3,
      '$.homePaidCurrent', 130532, '$.homePaidStepMin', 1.10, '$.homePaidStepMax', 5.50,
      '$.homeDisplayEnabled', CAST('false' AS JSON),
      '$.homeDisplayAnchorAt', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)),
    c.updated_at = NOW(),
    v.config_value = CAST(CAST(v.config_value AS UNSIGNED) + 1 AS CHAR),
    v.updated_at = NOW()
WHERE v.config_key = 'growth.public_stats.version' AND v.status = 1 AND v.is_deleted = 0
  AND JSON_VALID(c.config_value) AND JSON_TYPE(c.config_value) = 'OBJECT'
  AND JSON_CONTAINS_PATH(c.config_value, 'one', '$.homeDisplayEnabled') = 0;
