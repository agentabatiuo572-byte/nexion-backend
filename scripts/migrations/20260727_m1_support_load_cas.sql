-- M1 load policy CAS row. Materializing it lets SELECT ... FOR UPDATE serialize
-- two operators who submit the same visible version.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('content.support.load.version', '1', 'NUMBER', 'content_support_load', 'ADMIN', 'M1 support load optimistic-lock version', 1, 0)
ON DUPLICATE KEY UPDATE
  value_type='NUMBER', config_group='content_support_load', visibility='ADMIN',
  status=1, is_deleted=0;
