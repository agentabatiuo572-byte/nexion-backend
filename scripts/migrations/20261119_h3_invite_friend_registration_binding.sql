-- Repair the Day-One referral task and its trusted completion route together.
-- The canonical route matches 20260911_h3_day_one_registration_bindings.sql.
CREATE TEMPORARY TABLE h3_invite_friend_registration_assert (
  valid TINYINT NOT NULL,
  CONSTRAINT h3_invite_friend_registration_binding_required CHECK (valid = 1)
);

START TRANSACTION;
INSERT IGNORE INTO nx_admin_operation_mutex (lock_key, updated_at) VALUES ('H3_CONFIG', NOW());
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key = 'H3_CONFIG' FOR UPDATE;

-- Existing custom, disabled or deleted bindings are decisions to preserve.
SET @h3_invite_binding_conflict = EXISTS (
  SELECT 1 FROM nx_growth_quest_event_binding b
   WHERE (b.binding_code = 'REFERRAL_SETTLED' OR b.quest_code = 'invite_friend'
          OR (b.producer = 'REFERRAL' AND b.event_type = 'H8_REFERRAL_REWARD_SETTLED'
              AND b.user_id_field = 'inviter_user_id' AND b.status = 1 AND b.is_deleted = 0))
     AND NOT (b.quest_code = 'invite_friend' AND b.producer = 'REFERRAL'
              AND b.event_type = 'H8_REFERRAL_REWARD_SETTLED' AND b.user_id_field = 'inviter_user_id'
              AND b.status = 1 AND b.is_deleted = 0 AND TRIM(b.binding_code) <> '')
);

INSERT INTO nx_mission
  (mission_code, mission_name, mission_type, mission_category, action_route,
   reward_points, status, created_at, updated_at, is_deleted)
SELECT 'invite_friend', 'Invite a friend', 'DAY_ONE', 'SOCIAL', '/pages/team/team',
       200, 1, NOW(), NOW(), 0
 WHERE NOT @h3_invite_binding_conflict
   AND NOT EXISTS (SELECT 1 FROM nx_mission WHERE mission_code = 'invite_friend');

INSERT INTO nx_growth_quest_event_binding
  (binding_code, producer, event_type, quest_code, user_id_field, status, is_deleted)
SELECT 'REFERRAL_SETTLED', 'REFERRAL', 'H8_REFERRAL_REWARD_SETTLED', 'invite_friend', 'inviter_user_id', 1, 0
 WHERE NOT @h3_invite_binding_conflict
   AND EXISTS (SELECT 1 FROM nx_mission
                WHERE mission_code = 'invite_friend' AND mission_type = 'DAY_ONE' AND status = 1 AND is_deleted = 0)
   AND NOT EXISTS (SELECT 1 FROM nx_growth_quest_event_binding WHERE quest_code = 'invite_friend');

-- Require the same minimum usable binding as the registration snapshot.
INSERT INTO h3_invite_friend_registration_assert
SELECT 0 FROM nx_mission m
 WHERE m.mission_code = 'invite_friend' AND m.mission_type = 'DAY_ONE' AND m.status = 1 AND m.is_deleted = 0
   AND NOT EXISTS (
     SELECT 1 FROM nx_growth_quest_event_binding b
      WHERE b.quest_code = m.mission_code AND b.status = 1 AND b.is_deleted = 0
        AND TRIM(b.binding_code) <> '' AND TRIM(b.producer) <> ''
        AND TRIM(b.event_type) <> '' AND TRIM(b.user_id_field) <> ''
   );
COMMIT;

DROP TEMPORARY TABLE h3_invite_friend_registration_assert;
