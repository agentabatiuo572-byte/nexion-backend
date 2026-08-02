-- J4 validation-only drills have no production side effect to reverse.  A NULL
-- rollback state is therefore ambiguous and must never be treated as terminal.
-- This controlled one-shot repair is deliberately narrow: it only touches
-- SOP-CUSTOM-10-DRILL-211D828E when its durable execution JSON and required
-- completion audit agree.
SET NAMES utf8mb4;

-- A named MySQL advisory lock serializes every candidate read, state update and
-- audit insert. If it cannot be acquired, the enforced temporary-table check
-- aborts before the migration can write any durable row; connection close also
-- releases a held advisory lock on any later failure.
SELECT GET_LOCK('nexion:migration:j4:drill-terminal-state', 30)
  INTO @j4_drill_terminal_state_lock;
DROP TEMPORARY TABLE IF EXISTS tmp_j4_drill_terminal_state_lock_guard;
CREATE TEMPORARY TABLE tmp_j4_drill_terminal_state_lock_guard (
  acquired TINYINT NOT NULL,
  CONSTRAINT ck_j4_drill_terminal_state_lock CHECK (acquired = 1)
);
INSERT INTO tmp_j4_drill_terminal_state_lock_guard (acquired)
VALUES (@j4_drill_terminal_state_lock);

-- Include candidate discovery and all durable repairs in one transaction. The
-- temporary-table DDL is connection-local and does not commit business rows.
START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS tmp_j4_validation_only_drill_repair;
CREATE TEMPORARY TABLE tmp_j4_validation_only_drill_repair AS
SELECT e.execution_id
  FROM nx_emergency_sop_execution e
 WHERE e.is_deleted = 0
   AND e.execution_id = 'SOP-CUSTOM-10-DRILL-211D828E'
   AND e.execution_mode = 'drill'
   AND JSON_TYPE(e.step_status_json) = 'ARRAY'
   AND JSON_LENGTH(e.step_status_json) > 0
   AND NOT EXISTS (
     SELECT 1
       FROM JSON_TABLE(e.step_status_json, '$[*]'
              COLUMNS (status VARCHAR(32) PATH '$' NULL ON ERROR)) step_status
      WHERE step_status.status IS NULL OR step_status.status <> 'done'
   )
   AND JSON_TYPE(e.domain_action_json) = 'ARRAY'
   AND JSON_LENGTH(e.domain_action_json) > 0
   AND NOT EXISTS (
     SELECT 1
       FROM JSON_TABLE(e.domain_action_json, '$[*]'
              COLUMNS (status VARCHAR(32) PATH '$.status' NULL ON ERROR)) action
      WHERE action.status IS NULL OR action.status <> 'VALIDATED'
   )
   AND EXISTS (
     SELECT 1
       FROM nx_audit_log audit
      WHERE audit.is_deleted = 0
        AND audit.action = 'J4_SOP_PLAYBOOK_DRILL_COMPLETED'
        AND audit.resource_type = 'SOP_PLAYBOOK_EXECUTION'
        AND audit.resource_id = 'SOP-CUSTOM-10-DRILL-211D828E'
        AND audit.resource_id = e.execution_id
        AND audit.result = 'SUCCESS'
        AND JSON_UNQUOTE(JSON_EXTRACT(audit.detail_json, '$.validationOnly')) = 'true'
        AND JSON_UNQUOTE(JSON_EXTRACT(audit.detail_json, '$.productionActionsExecuted')) = 'false'
   );

-- The target must resolve to exactly one independently proven execution. Both
-- a missing target and any unexpected multiplicity fail before any durable
-- write. This is intentionally an exact one-shot, not a bulk backfill.
DROP TEMPORARY TABLE IF EXISTS tmp_j4_drill_terminal_state_candidate_guard;
CREATE TEMPORARY TABLE tmp_j4_drill_terminal_state_candidate_guard (
  candidate_count TINYINT UNSIGNED NOT NULL,
  CONSTRAINT ck_j4_drill_terminal_state_candidate_count CHECK (candidate_count = 1)
);
INSERT INTO tmp_j4_drill_terminal_state_candidate_guard (candidate_count)
SELECT COUNT(*) FROM tmp_j4_validation_only_drill_repair;

-- A re-run is safe only when the exact target is already terminal and has one
-- matching migration audit; all partial or contradictory states fail closed.
DROP TEMPORARY TABLE IF EXISTS tmp_j4_drill_terminal_state_rerun_guard;
CREATE TEMPORARY TABLE tmp_j4_drill_terminal_state_rerun_guard (
  rerun_state_valid TINYINT NOT NULL,
  CONSTRAINT ck_j4_drill_terminal_state_rerun_state CHECK (rerun_state_valid = 1)
);
INSERT INTO tmp_j4_drill_terminal_state_rerun_guard (rerun_state_valid)
SELECT CASE WHEN COUNT(*) = 1
              AND SUM(CASE WHEN (
                    ((e.rollback_status IS NULL OR e.rollback_status = '')
                     AND (e.rollback_reason IS NULL OR e.rollback_reason = '')
                     AND e.rollback_at IS NULL
                     AND (e.rollback_action_json IS NULL
                          OR JSON_TYPE(e.rollback_action_json) = 'NULL'
                          OR JSON_LENGTH(e.rollback_action_json) = 0
                          OR (JSON_TYPE(e.rollback_action_json) = 'STRING'
                              AND JSON_UNQUOTE(e.rollback_action_json) = ''))
                     AND (SELECT COUNT(*)
                            FROM nx_audit_log all_history_pending_audit
                           WHERE all_history_pending_audit.action = 'J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED'
                             AND all_history_pending_audit.resource_type = 'SOP_PLAYBOOK_EXECUTION'
                             AND all_history_pending_audit.resource_id = 'SOP-CUSTOM-10-DRILL-211D828E') = 0)
                    OR
                    (e.rollback_status = 'NOT_REQUIRED'
                     AND e.rollback_reason = 'VALIDATION_ONLY_NO_PRODUCTION_ACTIONS'
                     AND e.rollback_at IS NULL
                     AND (e.rollback_action_json IS NULL
                          OR JSON_TYPE(e.rollback_action_json) = 'NULL'
                          OR JSON_LENGTH(e.rollback_action_json) = 0
                          OR (JSON_TYPE(e.rollback_action_json) = 'STRING'
                              AND JSON_UNQUOTE(e.rollback_action_json) = ''))
                     AND (SELECT COUNT(*)
                            FROM nx_audit_log all_history_completed_audit
                           WHERE all_history_completed_audit.action = 'J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED'
                             AND all_history_completed_audit.resource_type = 'SOP_PLAYBOOK_EXECUTION'
                             AND all_history_completed_audit.resource_id = 'SOP-CUSTOM-10-DRILL-211D828E') = 1
                     AND (SELECT COUNT(*)
                            FROM nx_audit_log valid_completed_audit
                           WHERE valid_completed_audit.is_deleted = 0
                             AND valid_completed_audit.action = 'J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED'
                             AND valid_completed_audit.resource_type = 'SOP_PLAYBOOK_EXECUTION'
                             AND valid_completed_audit.resource_id = 'SOP-CUSTOM-10-DRILL-211D828E'
                             AND JSON_UNQUOTE(JSON_EXTRACT(valid_completed_audit.detail_json, '$.migration')) = '20260801_j4_drill_terminal_state'
                             AND JSON_UNQUOTE(JSON_EXTRACT(valid_completed_audit.detail_json, '$.rollbackStatus')) = 'NOT_REQUIRED'
                             AND JSON_UNQUOTE(JSON_EXTRACT(valid_completed_audit.detail_json, '$.rollbackReason')) = 'VALIDATION_ONLY_NO_PRODUCTION_ACTIONS') = 1)
                  ) THEN 1 ELSE 0 END) = 1
            THEN 1 ELSE 0 END
  FROM tmp_j4_validation_only_drill_repair candidate
  JOIN nx_emergency_sop_execution e
    ON e.execution_id = candidate.execution_id
 WHERE candidate.execution_id = 'SOP-CUSTOM-10-DRILL-211D828E'
   AND e.execution_id = 'SOP-CUSTOM-10-DRILL-211D828E'
   AND e.is_deleted = 0;

UPDATE nx_emergency_sop_execution e
JOIN tmp_j4_validation_only_drill_repair candidate
  ON candidate.execution_id = e.execution_id
 AND candidate.execution_id = 'SOP-CUSTOM-10-DRILL-211D828E'
   SET e.rollback_status = 'NOT_REQUIRED',
       e.rollback_reason = 'VALIDATION_ONLY_NO_PRODUCTION_ACTIONS',
       e.rollback_at = NULL,
       e.updated_at = NOW()
 WHERE e.is_deleted = 0
   AND e.execution_id = 'SOP-CUSTOM-10-DRILL-211D828E'
   AND (e.rollback_status IS NULL OR e.rollback_status = '')
   AND (e.rollback_reason IS NULL OR e.rollback_reason = '')
   AND e.rollback_at IS NULL
   AND (e.rollback_action_json IS NULL
        OR JSON_TYPE(e.rollback_action_json) = 'NULL'
        OR JSON_LENGTH(e.rollback_action_json) = 0
        OR (JSON_TYPE(e.rollback_action_json) = 'STRING'
            AND JSON_UNQUOTE(e.rollback_action_json) = ''))
   AND (SELECT COUNT(*)
          FROM nx_audit_log all_history_pending_audit
         WHERE all_history_pending_audit.action = 'J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED'
           AND all_history_pending_audit.resource_type = 'SOP_PLAYBOOK_EXECUTION'
           AND all_history_pending_audit.resource_id = 'SOP-CUSTOM-10-DRILL-211D828E') = 0;

-- Append one audit fact per migrated execution. Re-running this file changes no
-- already-finalized row and cannot duplicate the repair evidence.
INSERT INTO nx_audit_log (
  trace_id, service_name, action, resource_type, resource_id, biz_no,
  actor_type, actor_username, result, risk_level, detail_json, created_at, is_deleted
)
SELECT
  CONCAT('migration:j4-drill-terminal:', candidate.execution_id),
  'nexion-ops-console',
  'J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED',
  'SOP_PLAYBOOK_EXECUTION',
  candidate.execution_id,
  candidate.execution_id,
  'SYSTEM',
  'migration:j4-drill-terminal-state',
  'SUCCESS',
  'MEDIUM',
  JSON_OBJECT(
    'migration', '20260801_j4_drill_terminal_state',
    'validationOnly', true,
    'productionActionsExecuted', false,
    'rollbackStatus', 'NOT_REQUIRED',
    'rollbackReason', 'VALIDATION_ONLY_NO_PRODUCTION_ACTIONS'
  ),
  NOW(),
  0
FROM tmp_j4_validation_only_drill_repair candidate
JOIN nx_emergency_sop_execution e ON e.execution_id = candidate.execution_id
WHERE candidate.execution_id = 'SOP-CUSTOM-10-DRILL-211D828E'
  AND e.execution_id = 'SOP-CUSTOM-10-DRILL-211D828E'
  AND e.is_deleted = 0
  AND e.rollback_status = 'NOT_REQUIRED'
  AND e.rollback_reason = 'VALIDATION_ONLY_NO_PRODUCTION_ACTIONS'
  AND e.rollback_at IS NULL
  AND (e.rollback_action_json IS NULL
       OR JSON_TYPE(e.rollback_action_json) = 'NULL'
       OR JSON_LENGTH(e.rollback_action_json) = 0
       OR (JSON_TYPE(e.rollback_action_json) = 'STRING'
           AND JSON_UNQUOTE(e.rollback_action_json) = ''))
  AND (SELECT COUNT(*)
         FROM nx_audit_log all_history_pending_audit
        WHERE all_history_pending_audit.action = 'J4_SOP_DRILL_ROLLBACK_NOT_REQUIRED_MIGRATED'
          AND all_history_pending_audit.resource_type = 'SOP_PLAYBOOK_EXECUTION'
          AND all_history_pending_audit.resource_id = 'SOP-CUSTOM-10-DRILL-211D828E') = 0;

COMMIT;

DROP TEMPORARY TABLE IF EXISTS tmp_j4_validation_only_drill_repair;
DROP TEMPORARY TABLE IF EXISTS tmp_j4_drill_terminal_state_candidate_guard;
DROP TEMPORARY TABLE IF EXISTS tmp_j4_drill_terminal_state_rerun_guard;

SELECT RELEASE_LOCK('nexion:migration:j4:drill-terminal-state')
  INTO @j4_drill_terminal_state_release;
DROP TEMPORARY TABLE IF EXISTS tmp_j4_drill_terminal_state_release_guard;
CREATE TEMPORARY TABLE tmp_j4_drill_terminal_state_release_guard (
  released TINYINT NOT NULL,
  CONSTRAINT ck_j4_drill_terminal_state_release CHECK (released = 1)
);
INSERT INTO tmp_j4_drill_terminal_state_release_guard (released)
VALUES (@j4_drill_terminal_state_release);

DROP TEMPORARY TABLE IF EXISTS tmp_j4_drill_terminal_state_lock_guard;
DROP TEMPORARY TABLE IF EXISTS tmp_j4_drill_terminal_state_release_guard;
