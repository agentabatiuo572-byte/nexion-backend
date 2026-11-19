-- Keep the trusted mission alignment from 20260902. Its historical NEX-only
-- reward repair would reset valid UVT-denominated policies on every startup.
START TRANSACTION;

UPDATE nx_growth_quest_event_binding
   SET quest_code = 'invite_friend', status = 1, updated_at = NOW()
 WHERE binding_code = 'REFERRAL_SETTLED' AND is_deleted = 0
   AND (quest_code <> 'invite_friend' OR status <> 1);

UPDATE nx_growth_quest_event_binding
   SET status = 0, updated_at = NOW()
 WHERE binding_code IN ('ORDER_STARTED','LEARNING_COMPLETED','DEVICE_ACTIVATED','COMMISSION_UNLOCKED')
   AND is_deleted = 0 AND status <> 0;

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('growth.quest.day_one.tri_reward','30 / 10 / 0 UVT','STRING','growth','ADMIN','H3 launch Day-One reward ladder',1,0),
  ('growth.quest.day_one.eligibility_hours','72','NUMBER','growth','ADMIN','H3 Day-One eligibility window',1,0)
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

COMMIT;
