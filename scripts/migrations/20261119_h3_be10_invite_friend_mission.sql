-- H3-BE-10: invite_friend is still the active Day-One referral target
-- (REFERRAL_SETTLED binding points at it), but the mission row is missing from
-- the publishable directory. Add it once; an existing row is left untouched so
-- operator-owned fields are never overwritten. Idempotent.
START TRANSACTION;

INSERT INTO nx_mission
  (mission_code, mission_name, mission_type, mission_category, action_route,
   reward_points, status, created_at, updated_at, is_deleted)
SELECT 'invite_friend', 'Invite a friend', 'DAY_ONE', 'SOCIAL', '/pages/team/team',
       200, 1, NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM nx_mission WHERE mission_code = 'invite_friend'
);

-- Keep the existing active referral binding aligned with the now-present target.
UPDATE nx_growth_quest_event_binding
   SET quest_code = 'invite_friend', status = 1, updated_at = NOW()
 WHERE binding_code = 'REFERRAL_SETTLED'
   AND is_deleted = 0;

COMMIT;
