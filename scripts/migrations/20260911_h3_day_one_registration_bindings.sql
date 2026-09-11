-- Forward-only repair for the six current DAY_ONE definitions. No user snapshots,
-- task states, rewards or deadlines are changed. Run with mysql without --force.
SET NAMES utf8mb4;
-- One receipt is inserted in the same transaction as the Day One source outbox event.
CREATE TABLE IF NOT EXISTS nx_growth_day_one_page_observation_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  instance_key VARCHAR(64) NOT NULL,
  surface VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_h3_day_one_page_observation_receipt (user_id, instance_key, surface)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_day_one_business_fact_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  instance_key VARCHAR(64) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_h3_day_one_business_fact (user_id,instance_key,event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TEMPORARY TABLE h3_registration_expected (
  binding_code VARCHAR(48), producer VARCHAR(32), event_type VARCHAR(128),
  quest_code VARCHAR(64) PRIMARY KEY, user_id_field VARCHAR(64)
);
INSERT INTO h3_registration_expected VALUES
 ('DAY_ONE_CARD_BOUND','SYSTEM','H3_DAY_ONE_CARD_BOUND','bind_bank_card','user_id'),
 ('DAY_ONE_EARN_VIEWED','SYSTEM','H3_DAY_ONE_EARN_PAGE_VIEWED','visit_earn','user_id'),
 ('DAY_ONE_STORE_VIEWED','SYSTEM','H3_DAY_ONE_STORE_PAGE_VIEWED','visit_store','user_id'),
 ('DAY_ONE_S1_ROI_VIEWED','SYSTEM','H3_DAY_ONE_S1_ROI_VIEWED','view_product_roi','user_id'),
 ('DAY_ONE_PROFILE_SAVED','SYSTEM','H3_DAY_ONE_PROFILE_SAVED','setup_profile','user_id'),
 ('REFERRAL_SETTLED','REFERRAL','H8_REFERRAL_REWARD_SETTLED','invite_friend','inviter_user_id');
CREATE TEMPORARY TABLE h3_registration_assert (
  valid TINYINT NOT NULL,
  CONSTRAINT h3_registration_binding_conflict CHECK (valid=1)
);

START TRANSACTION;
INSERT IGNORE INTO nx_admin_operation_mutex(lock_key,updated_at) VALUES('H3_CONFIG',NOW());
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H3_CONFIG' FOR UPDATE;
SELECT id FROM nx_mission WHERE mission_type='DAY_ONE' AND status=1 AND is_deleted=0 ORDER BY id FOR UPDATE;

-- Preserve operator-owned slots and codes. An inconsistent existing mapping stops
-- the migration instead of silently rerouting an event or overwriting a decision.
INSERT INTO h3_registration_assert
SELECT IF(EXISTS (
 SELECT 1 FROM h3_registration_expected e
 JOIN nx_mission m ON m.mission_code=e.quest_code AND m.mission_type='DAY_ONE' AND m.status=1 AND m.is_deleted=0
 JOIN nx_growth_quest_event_binding b ON
   b.binding_code=e.binding_code OR
   (b.quest_code=e.quest_code AND b.status=1 AND b.is_deleted=0) OR
   (b.producer=e.producer AND b.event_type=e.event_type AND b.user_id_field=e.user_id_field AND b.status=1 AND b.is_deleted=0)
 WHERE NOT (b.producer=e.producer AND b.event_type=e.event_type AND b.quest_code=e.quest_code
            AND b.user_id_field=e.user_id_field AND b.is_deleted=0)
),0,1);

-- Existing exact rows keep their binding code/id; only enable the known source
-- for an already enabled task. Do not introduce extra slots for paused tasks.
UPDATE nx_growth_quest_event_binding b
JOIN h3_registration_expected e ON b.producer=e.producer AND b.event_type=e.event_type
 AND b.quest_code=e.quest_code AND b.user_id_field=e.user_id_field
JOIN nx_mission m ON m.mission_code=e.quest_code AND m.mission_type='DAY_ONE' AND m.status=1 AND m.is_deleted=0
SET b.status=1,b.updated_at=NOW()
WHERE b.is_deleted=0 AND b.status<>1;

INSERT INTO nx_growth_quest_event_binding
 (binding_code,producer,event_type,quest_code,user_id_field,status,is_deleted)
SELECT e.binding_code,e.producer,e.event_type,e.quest_code,e.user_id_field,1,0
FROM h3_registration_expected e
JOIN nx_mission m ON m.mission_code=e.quest_code AND m.mission_type='DAY_ONE' AND m.status=1 AND m.is_deleted=0
WHERE NOT EXISTS (
 SELECT 1 FROM nx_growth_quest_event_binding b WHERE b.producer=e.producer AND b.event_type=e.event_type
 AND b.quest_code=e.quest_code AND b.user_id_field=e.user_id_field AND b.is_deleted=0 AND b.status=1
);

-- Same all-enabled-tasks invariant as registration, including custom missions.
INSERT INTO h3_registration_assert
SELECT IF(EXISTS (
 SELECT 1 FROM nx_mission m WHERE m.mission_type='DAY_ONE' AND m.status=1 AND m.is_deleted=0
 AND NOT EXISTS (SELECT 1 FROM nx_growth_quest_event_binding b
   WHERE b.quest_code=m.mission_code AND b.status=1 AND b.is_deleted=0
   AND TRIM(b.binding_code)<>'' AND TRIM(b.producer)<>'' AND TRIM(b.event_type)<>'' AND TRIM(b.user_id_field)<>'')
),0,1);
COMMIT;
DROP TEMPORARY TABLE h3_registration_assert;
DROP TEMPORARY TABLE h3_registration_expected;
