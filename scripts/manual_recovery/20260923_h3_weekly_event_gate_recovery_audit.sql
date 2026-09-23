-- Audit the exact mission statuses changed by the H3 weekly rollout. Existing
-- paused definitions have no receipt and must never be restored by this script.
SELECT r.id receipt_id,r.mission_code,r.reason,r.previous_status,r.paused_at,
       m.status current_status,m.updated_at current_updated_at,
       CASE WHEN m.status=0 AND m.updated_at=r.paused_at AND r.previous_status=1
            THEN 'ELIGIBLE_FOR_OPERATOR_REVIEW' ELSE 'CHANGED_SINCE_PAUSE' END recovery_state
  FROM nx_growth_mission_gate_pause_receipt r
  JOIN nx_mission m ON m.id=r.mission_id AND m.mission_code=r.mission_code
 ORDER BY r.id;

-- After verifying the original business source and exact normative binding,
-- set one audited receipt id and execute. Zero is intentionally a no-op.
SET @h3_restore_receipt_id := 0;
START TRANSACTION;
UPDATE nx_mission m
  JOIN nx_growth_mission_gate_pause_receipt r
    ON r.mission_id=m.id AND r.mission_code=m.mission_code
   SET m.status=r.previous_status,m.updated_at=NOW(3)
 WHERE r.id=@h3_restore_receipt_id AND r.previous_status=1
   AND m.status=0 AND m.updated_at=r.paused_at AND m.is_deleted=0;
SELECT ROW_COUNT() restored_missions;
COMMIT;
