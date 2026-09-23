-- Ensure H4 has a writable localization branch without publishing unreviewed copy.
-- Existing operator content remains authoritative. The H4 publish gate checks
-- required locale fields when an event is moved to ongoing.

INSERT IGNORE INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('growth.content.localized', '{}', 'JSON', 'growth', 'ADMIN',
   'H3/H4 operator-authored localized content', 1, 0);

-- JSON_SET cannot add a nested member when its parent object is absent.
-- Add only the missing parent; malformed or non-object operator data is left
-- untouched for explicit repair instead of being overwritten during startup.
UPDATE nx_config_item
   SET config_value = JSON_SET(config_value, '$.event', JSON_OBJECT()),
       updated_at = NOW()
 WHERE config_key = 'growth.content.localized'
   AND status = 1 AND is_deleted = 0
   AND JSON_VALID(config_value)
   AND JSON_TYPE(IF(JSON_VALID(config_value), config_value, '{}')) = 'OBJECT'
   AND JSON_EXTRACT(IF(JSON_VALID(config_value), config_value, '{}'), '$.event') IS NULL;
