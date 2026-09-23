-- Bind only server-verified weekly facts. Existing operator bindings, including
-- disabled ones, are preserved. Never manufacture completion from page clicks.
CREATE TABLE IF NOT EXISTS nx_growth_mission_business_gate_rollout (
  mission_code VARCHAR(64) NOT NULL PRIMARY KEY,
  first_applied_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_mission_gate_pause_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  mission_id BIGINT NOT NULL,
  mission_code VARCHAR(64) NOT NULL,
  reason VARCHAR(64) NOT NULL,
  previous_status TINYINT NOT NULL,
  paused_at DATETIME(3) NOT NULL,
  KEY idx_h3_pause_mission (mission_id,paused_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_quest_binding_quarantine_receipt (
  binding_id BIGINT NOT NULL PRIMARY KEY,
  binding_code VARCHAR(48) NOT NULL,
  previous_quest_code VARCHAR(64) NOT NULL,
  previous_event_type VARCHAR(128) NOT NULL,
  quarantined_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;
INSERT IGNORE INTO nx_admin_operation_mutex(lock_key,updated_at) VALUES('H3_CONFIG',NOW());
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H3_CONFIG' FOR UPDATE;
SET @h3_weekly_gate_rollout_at := NOW(3);
-- nx_mission.updated_at is DATETIME(0). Its pause marker and receipt must
-- have equal precision for the selected rollback to detect later edits.
SET @h3_weekly_pause_at := NOW();

DROP TEMPORARY TABLE IF EXISTS h3_weekly_expected;
DROP TEMPORARY TABLE IF EXISTS h3_weekly_quarantine_ids;
CREATE TEMPORARY TABLE h3_weekly_expected (
  binding_code VARCHAR(48) NOT NULL,
  quest_code VARCHAR(64) NOT NULL PRIMARY KEY,
  event_type VARCHAR(128) NOT NULL
);
INSERT INTO h3_weekly_expected VALUES
  ('WEEKLY_STORE_THREE_PRODUCTS','weekly_t2_browse_store','H3_STOREFRONT_THREE_PRODUCTS_VIEWED'),
  ('WEEKLY_INVITE_REGISTERED','weekly_t2_invite_friend','H3_REFERRAL_REGISTERED'),
  ('WEEKLY_NEX_EXCHANGED','weekly_t2_nex_swap','H3_EXCHANGE_COMPLETED'),
  ('WEEKLY_COMPUTE_50','weekly_t2_ai_jobs_50','H3_COMPUTE_COMPLETED_50'),
  ('WEEKLY_GENESIS_MARKET_VIEWED','weekly_t2_genesis_browse','H3_GENESIS_SECONDARY_MARKET_VIEWED');

CREATE TEMPORARY TABLE h3_weekly_quarantine_ids (binding_id BIGINT NOT NULL PRIMARY KEY);
INSERT IGNORE INTO h3_weekly_quarantine_ids(binding_id)
SELECT DISTINCT b.id
  FROM nx_growth_quest_event_binding b
 WHERE b.status=1 AND b.is_deleted=0
   AND EXISTS (SELECT 1 FROM h3_weekly_expected e
                WHERE b.event_type=e.event_type OR b.quest_code=e.quest_code
                   OR b.binding_code=e.binding_code)
   AND NOT EXISTS (SELECT 1 FROM h3_weekly_expected e
                    WHERE b.producer='SYSTEM' AND b.user_id_field='user_id'
                      AND b.event_type=e.event_type AND b.quest_code=e.quest_code);

-- A historical mapping may occupy a verified event or mission slot. Keep its
-- original row for audit, disable reward projection, then repair the free slot.
INSERT IGNORE INTO nx_growth_quest_binding_quarantine_receipt
  (binding_id,binding_code,previous_quest_code,previous_event_type,quarantined_at)
SELECT b.id,b.binding_code,b.quest_code,b.event_type,NOW(3)
  FROM nx_growth_quest_event_binding b
  JOIN h3_weekly_quarantine_ids q ON q.binding_id=b.id;
UPDATE nx_growth_quest_event_binding b
JOIN h3_weekly_quarantine_ids q ON q.binding_id=b.id
   SET b.status=0,b.updated_at=NOW()
 WHERE b.status=1 AND b.is_deleted=0;

-- A quarantined row retains its unique binding_code. Use a stable alternate
-- code if it owns the intended one; never overwrite an operator's disabled row.
INSERT INTO nx_growth_quest_event_binding
  (binding_code,producer,event_type,quest_code,user_id_field,status,created_at,updated_at,is_deleted)
SELECT CASE WHEN EXISTS (SELECT 1 FROM nx_growth_quest_event_binding occupied
                          WHERE occupied.binding_code=e.binding_code)
            THEN CONCAT('H3WK_',SUBSTRING(SHA2(e.quest_code,256),1,32))
            ELSE e.binding_code END,
       'SYSTEM',e.event_type,m.mission_code,'user_id',1,NOW(),NOW(),0
  FROM h3_weekly_expected e
  JOIN nx_mission m ON m.mission_code=e.quest_code AND m.mission_type='WEEKLY_T2'
 WHERE m.status IN (0,1) AND m.is_deleted=0
   AND NOT EXISTS (
     SELECT 1 FROM nx_growth_quest_event_binding b
      WHERE b.quest_code=m.mission_code AND b.is_deleted=0
        AND NOT EXISTS (SELECT 1 FROM nx_growth_quest_binding_quarantine_receipt q
                         WHERE q.binding_id=b.id AND b.status=0
                           AND q.previous_quest_code=b.quest_code
                           AND q.previous_event_type=b.event_type))
   AND NOT EXISTS (SELECT 1 FROM nx_growth_quest_event_binding b
                    WHERE b.producer='SYSTEM' AND b.event_type=e.event_type
                      AND b.quest_code=e.quest_code AND b.user_id_field='user_id')
   AND NOT EXISTS (SELECT 1 FROM nx_growth_quest_event_binding b
                    WHERE b.binding_code=CONCAT('H3WK_',SUBSTRING(SHA2(e.quest_code,256),1,32)));

-- Historical active definitions without a completion route cannot stay live.
-- Pause definitions only; user progress and already granted rewards remain.
INSERT INTO nx_growth_mission_gate_pause_receipt
  (mission_id,mission_code,reason,previous_status,paused_at)
SELECT m.id,m.mission_code,'NO_VERIFIED_COMPLETION_BINDING',m.status,@h3_weekly_pause_at
  FROM nx_mission m
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
UPDATE nx_mission m SET m.status=0,m.updated_at=@h3_weekly_pause_at
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
INSERT INTO nx_growth_mission_gate_pause_receipt
  (mission_id,mission_code,reason,previous_status,paused_at)
SELECT m.id,m.mission_code,'BUSINESS_OR_COMPLETION_UNAVAILABLE',m.status,@h3_weekly_pause_at
  FROM nx_mission m
  JOIN nx_growth_mission_business_gate_rollout r ON r.mission_code=m.mission_code
 WHERE r.first_applied_at=@h3_weekly_gate_rollout_at AND m.status=1 AND m.is_deleted=0;
UPDATE nx_mission m
JOIN nx_growth_mission_business_gate_rollout r ON r.mission_code=m.mission_code
 SET m.status=0,m.updated_at=@h3_weekly_pause_at
 WHERE r.first_applied_at=@h3_weekly_gate_rollout_at
   AND m.status=1 AND m.is_deleted=0;
DROP TEMPORARY TABLE h3_weekly_expected;
DROP TEMPORARY TABLE h3_weekly_quarantine_ids;
COMMIT;
