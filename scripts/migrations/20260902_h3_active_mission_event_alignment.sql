-- Align trusted completion routes to the current PC-managed H3 mission set.
-- Historical H3_* definitions remain recoverable but must not receive new facts while inactive.
UPDATE nx_growth_quest_event_binding
   SET quest_code='invite_friend', status=1, updated_at=NOW()
 WHERE binding_code='REFERRAL_SETTLED'
   AND is_deleted=0;

UPDATE nx_growth_quest_event_binding
   SET status=0, updated_at=NOW()
 WHERE binding_code IN ('ORDER_STARTED','LEARNING_COMPLETED','DEVICE_ACTIVATED','COMMISSION_UNLOCKED')
   AND is_deleted=0;

-- Repair only malformed/single-value policies; preserve any valid operator-owned three-tier ladder.
UPDATE nx_config_item
   SET config_value='500 / 200 / 0 NEX', updated_at=NOW()
 WHERE config_key='growth.quest.day_one.tri_reward'
   AND status=1 AND is_deleted=0
   AND config_value NOT REGEXP '^[[:space:]]*[0-9]+([.][0-9]+)?[[:space:]]*/[[:space:]]*[0-9]+([.][0-9]+)?[[:space:]]*/[[:space:]]*0([.]0+)?[[:space:]]*NEX[[:space:]]*$';
