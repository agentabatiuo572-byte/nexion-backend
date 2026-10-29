-- Product policy: six physical activation slots per account. Cloud Share is not a physical slot.
-- Correct the persisted legacy limit as well as the code fallback. MySQL 8, rerunnable.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('device.max_active_slots', '6', 'INTEGER', 'device', 'ADMIN', 'Six physical activation slots per account', 1, 0)
ON DUPLICATE KEY UPDATE
  config_value = '6', status = 1, is_deleted = 0, updated_at = NOW();
