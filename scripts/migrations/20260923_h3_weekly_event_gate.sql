-- Bind only server-verified weekly facts. Existing operator bindings, including
-- disabled ones, are preserved. Never manufacture completion from page clicks.
CREATE TABLE IF NOT EXISTS nx_growth_mission_business_gate_rollout (
  mission_code VARCHAR(64) NOT NULL PRIMARY KEY,
  first_applied_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;
INSERT IGNORE INTO nx_admin_operation_mutex(lock_key,updated_at) VALUES('H3_CONFIG',NOW());
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H3_CONFIG' FOR UPDATE;
SET @h3_weekly_gate_rollout_at := NOW(3);

CREATE TEMPORARY TABLE h3_weekly_expected (
  binding_code VARCHAR(48) NOT NULL,
  quest_code VARCHAR(64) NOT NULL PRIMARY KEY,
  event_type VARCHAR(128) NOT NULL
);
INSERT INTO h3_weekly_expected VALUES
  ('WEEKLY_INVITE_REGISTERED','weekly_t2_invite_friend','H3_REFERRAL_REGISTERED'),
  ('WEEKLY_NEX_EXCHANGED','weekly_t2_nex_swap','H3_EXCHANGE_COMPLETED'),
  ('WEEKLY_COMPUTE_50','weekly_t2_ai_jobs_50','H3_COMPUTE_COMPLETED_50'),
  ('WEEKLY_GENESIS_MARKET_VIEWED','weekly_t2_genesis_browse','H3_GENESIS_SECONDARY_MARKET_VIEWED');

INSERT INTO nx_growth_quest_event_binding
  (binding_code,producer,event_type,quest_code,user_id_field,status,created_at,updated_at,is_deleted)
SELECT e.binding_code,'SYSTEM',e.event_type,m.mission_code,'user_id',1,NOW(),NOW(),0
  FROM h3_weekly_expected e
  JOIN nx_mission m ON m.mission_code=e.quest_code AND m.mission_type='WEEKLY_T2'
 WHERE m.status IN (0,1) AND m.is_deleted=0
   AND NOT EXISTS (
     SELECT 1 FROM nx_growth_quest_event_binding b
      WHERE b.binding_code=e.binding_code
         OR (b.quest_code=m.mission_code AND b.is_deleted=0)
         OR (b.producer='SYSTEM' AND b.event_type=e.event_type
             AND b.user_id_field='user_id' AND b.status=1 AND b.is_deleted=0));

-- Historical active definitions without a completion route cannot stay live.
-- Pause definitions only; user progress and already granted rewards remain.
UPDATE nx_mission m SET m.status=0,m.updated_at=NOW()
 WHERE m.status=1 AND m.is_deleted=0
   AND m.mission_type IN ('DAY_ONE','WEEKLY_T1','WEEKLY_T2')
   AND NOT EXISTS (SELECT 1 FROM nx_growth_quest_event_binding b
                    WHERE b.quest_code=m.mission_code AND b.status=1 AND b.is_deleted=0
                      AND (m.mission_code NOT IN
                           ('weekly_t2_browse_store','weekly_t2_invite_friend',
                            'weekly_t2_nex_swap','weekly_t2_ai_jobs_50','weekly_t2_genesis_browse')
                           OR (b.producer='SYSTEM' AND b.user_id_field='user_id'
                               AND b.event_type=CASE m.mission_code
                                 WHEN 'weekly_t2_browse_store' THEN 'H3_STOREFRONT_THREE_PRODUCTS_VIEWED'
                                 WHEN 'weekly_t2_invite_friend' THEN 'H3_REFERRAL_REGISTERED'
                                 WHEN 'weekly_t2_nex_swap' THEN 'H3_EXCHANGE_COMPLETED'
                                 WHEN 'weekly_t2_ai_jobs_50' THEN 'H3_COMPUTE_COMPLETED_50'
                                 WHEN 'weekly_t2_genesis_browse' THEN 'H3_GENESIS_SECONDARY_MARKET_VIEWED' END)));

-- A one-time pause is needed for legacy tasks whose completion source or
-- target business is unavailable, even if they have an unrelated binding.
-- Later operator reactivation survives restarts, subject to publication gates.
INSERT IGNORE INTO nx_growth_mission_business_gate_rollout(mission_code,first_applied_at)
SELECT m.mission_code,@h3_weekly_gate_rollout_at FROM nx_mission m
 WHERE m.mission_code IN ('weekly_t1_nex_v2_lock','weekly_t1_stake_fallback',
                          'weekly_t2_stake_small','weekly_t1_buy_genesis',
                          'weekly_t2_genesis_browse','weekly_t1_buy_additional_hw',
                          'weekly_t1_tradein_upgrade','weekly_t1_upgrade_s1_to_pro_v2',
                          'weekly_t1_subscribe_premium','weekly_t1_buy_first_box',
                          'weekly_t1_topup_balance','weekly_t2_reinvest',
                          'weekly_t2_top_up_small')
   AND m.is_deleted=0;
UPDATE nx_mission m
JOIN nx_growth_mission_business_gate_rollout r ON r.mission_code=m.mission_code
 SET m.status=0,m.updated_at=NOW()
 WHERE r.first_applied_at=@h3_weekly_gate_rollout_at
   AND m.status=1 AND m.is_deleted=0;
DROP TEMPORARY TABLE h3_weekly_expected;
COMMIT;
