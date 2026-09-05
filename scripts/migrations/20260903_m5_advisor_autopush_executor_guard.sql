-- M5 has no production auto-push executor. Keep historical and newly seeded
-- policy rows disabled until an executor is implemented and accepted.
UPDATE nx_config_item
   SET config_value = 'off',
       remark = 'M5 advisor auto-push disabled: executor unavailable',
       updated_at = NOW()
 WHERE config_key = 'I.session.advisor.policy.enabled'
   AND is_deleted = 0;
