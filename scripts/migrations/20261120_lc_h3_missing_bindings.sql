-- Keep the Day One receipt schema and fill missing bindings without changing operator choices.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_growth_day_one_business_fact_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  instance_key VARCHAR(64) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_h3_day_one_business_fact (user_id,instance_key,event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TEMPORARY TABLE lc_h3_expected (
  binding_code VARCHAR(48), producer VARCHAR(32), event_type VARCHAR(128),
  quest_code VARCHAR(64) PRIMARY KEY, user_id_field VARCHAR(64)
);
INSERT INTO lc_h3_expected VALUES
 ('DAY_ONE_CARD_BOUND','SYSTEM','H3_DAY_ONE_CARD_BOUND','bind_bank_card','user_id'),
 ('DAY_ONE_EARN_VIEWED','SYSTEM','H3_DAY_ONE_EARN_PAGE_VIEWED','visit_earn','user_id'),
 ('DAY_ONE_STORE_VIEWED','SYSTEM','H3_DAY_ONE_STORE_PAGE_VIEWED','visit_store','user_id'),
 ('DAY_ONE_S1_ROI_VIEWED','SYSTEM','H3_DAY_ONE_S1_ROI_VIEWED','view_product_roi','user_id'),
 ('DAY_ONE_PROFILE_SAVED','SYSTEM','H3_DAY_ONE_PROFILE_SAVED','setup_profile','user_id'),
 ('REFERRAL_SETTLED','REFERRAL','H8_REFERRAL_REWARD_SETTLED','invite_friend','inviter_user_id');

START TRANSACTION;

INSERT IGNORE INTO nx_admin_operation_mutex (lock_key, updated_at) VALUES ('H3_CONFIG', NOW());
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key = 'H3_CONFIG' FOR UPDATE;

-- Match the invite installer: preserve an occupied code, an existing mission mapping
-- (including disabled/deleted rows), or a producer slot that is already active.
INSERT INTO nx_growth_quest_event_binding
  (binding_code, producer, event_type, quest_code, user_id_field, status, is_deleted)
SELECT e.binding_code, e.producer, e.event_type, e.quest_code, e.user_id_field, 1, 0
FROM lc_h3_expected e
JOIN nx_mission m ON m.mission_code = e.quest_code
 AND m.mission_type = 'DAY_ONE' AND m.status = 1 AND m.is_deleted = 0
WHERE NOT EXISTS (
  SELECT 1 FROM nx_growth_quest_event_binding b
  WHERE b.binding_code = e.binding_code OR b.quest_code = e.quest_code
     OR (b.producer = e.producer AND b.event_type = e.event_type AND b.user_id_field = e.user_id_field
         AND b.status = 1 AND b.is_deleted = 0)
);

COMMIT;

DROP TEMPORARY TABLE lc_h3_expected;
