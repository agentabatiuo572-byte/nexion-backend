-- M5 话术与模板配置权限收口：风控只读，客服写权限由控制器继续按客服主管身份二次校验。
DELETE rp
  FROM nx_admin_role_permission rp
  JOIN nx_admin_role r ON r.id = rp.role_id AND r.role_code = 'RISK'
  JOIN nx_admin_permission p ON p.id = rp.permission_id
 WHERE p.permission_code = 'service_m5_write';

-- Materialize every writable M5/M3 configuration key so SELECT ... FOR UPDATE can serialize
-- concurrent stale-page commands even before the first operator change.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('I.session.cat.advisor.enabled', 'on', 'BOOLEAN', 'content_session', 'ADMIN', 'M5 advisor conversation category', 1, 0),
  ('I.session.cat.support.enabled', 'on', 'BOOLEAN', 'content_session', 'ADMIN', 'M5 support conversation category', 1, 0),
  ('I.session.advisor.policy.enabled', 'on', 'BOOLEAN', 'content_session', 'ADMIN', 'M5 advisor auto-push switch', 1, 0),
  ('I.session.advisor.policy.delayMs', '1500', 'NUMBER', 'content_session', 'ADMIN', 'M5 advisor first-push delay', 1, 0),
  ('I.session.advisor.policy.cooldownHours', '24', 'NUMBER', 'content_session', 'ADMIN', 'M5 advisor push cooldown', 1, 0),
  ('I.session.advisor.policy.maxPerSession', '1', 'NUMBER', 'content_session', 'ADMIN', 'M5 advisor per-session push limit', 1, 0),
  ('I.session.advisor.policy.audience', '全量用户', 'STRING', 'content_session', 'ADMIN', 'M5 advisor audience option', 1, 0),
  ('I.session.workbench.timeoutFallback', 'off', 'BOOLEAN', 'content_session', 'ADMIN', 'M3 workbench timeout fallback', 1, 0)
ON DUPLICATE KEY UPDATE
  value_type=VALUES(value_type), config_group='content_session', visibility='ADMIN',
  status=1, is_deleted=0;
