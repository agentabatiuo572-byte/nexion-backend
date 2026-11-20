-- E5-BE-20: backfill operator pause state only where the old server-side
-- pause event and its audit row prove the same operator pause. The event
-- payload carries the exact device ids; the audit row carries the same user,
-- action, reason and transaction time. The proven operator reason moves to
-- ops_pause_reason and the old paused_reason is cleared so resume cannot keep
-- judging it as paused. Rows without that evidence stay untouched. A later
-- resume event (the latest event for the device) wins. Only old rows with
-- ops_paused=0 and an active owned runtime matching the event user are
-- migrated; telemetry/system reasons and already migrated new-format rows
-- remain unchanged.
SET NAMES utf8mb4;

UPDATE nx_user_device_runtime r
JOIN (
    SELECT chosen.device_id, chosen.user_id, chosen.event_type, chosen.created_at, chosen.payload
    FROM (
        SELECT o.aggregate_id AS user_id,
               jt.device_id,
               o.event_type,
               o.created_at,
               o.payload,
               ROW_NUMBER() OVER (PARTITION BY jt.device_id ORDER BY o.created_at DESC, o.id DESC) AS rn
        FROM nx_event_outbox o
        JOIN JSON_TABLE(o.payload, '$.device_ids[*]'
                        COLUMNS(device_id BIGINT PATH '$')) jt
        WHERE o.aggregate_type = 'E5_DEVICE_BATCH'
          AND o.event_type IN ('admin.device_paused', 'admin.device_resumed')
          AND o.is_deleted = 0
          AND JSON_VALID(o.payload) = 1
    ) chosen
    WHERE chosen.rn = 1
) latest ON latest.device_id = r.user_device_id
JOIN nx_user_device d ON d.id = r.user_device_id AND d.is_deleted = 0
SET r.ops_paused = 1,
    r.ops_pause_reason = LEFT(JSON_UNQUOTE(JSON_EXTRACT(latest.payload, '$.reason')), 128),
    r.paused_reason = NULL,
    r.updated_at = NOW()
WHERE latest.event_type = 'admin.device_paused'
  AND COALESCE(r.ops_paused, 0) = 0
  AND (r.ops_pause_reason IS NULL OR TRIM(r.ops_pause_reason) = '')
  AND latest.user_id = CAST(d.user_id AS CHAR)
  AND d.status IN ('ONLINE', 'BUSY', 'RUNNING', 'ACTIVE', 'OFFLINE')
  AND d.activated_at IS NOT NULL
  AND d.deactivated_at IS NULL
  AND UPPER(d.ownership_status) = 'OWNED'
  AND r.id IS NOT NULL
  AND r.is_deleted = 0
  AND NULLIF(TRIM(r.paused_reason), '') IS NOT NULL
  AND UPPER(TRIM(r.paused_reason)) NOT IN (
      'PHONE_OFFLINE', 'PHONE_LOW_BATTERY', 'ORDER_REFUND',
      'PHONE_REPLACED', 'USER_DEACTIVATED',
      'UNBIND', 'DEACTIVATE', 'REBIND', 'REPLACE', 'RECYCLE', 'SUSPEND', 'SUSPENDED'
  )
  AND r.paused_reason = LEFT(JSON_UNQUOTE(JSON_EXTRACT(latest.payload, '$.reason')), 64)
  AND EXISTS (
      SELECT 1
      FROM nx_audit_log a
      WHERE LOWER(a.action) = 'admin.device_paused'
        AND a.resource_type = 'USER_DEVICE_BATCH'
        AND a.resource_id = CAST(latest.user_id AS CHAR)
        AND a.result = 'SUCCESS'
        AND a.is_deleted = 0
        AND JSON_UNQUOTE(JSON_EXTRACT(a.detail_json, '$.reason')) =
            JSON_UNQUOTE(JSON_EXTRACT(latest.payload, '$.reason'))
        AND ABS(TIMESTAMPDIFF(SECOND, a.created_at, latest.created_at)) <= 2
  );
