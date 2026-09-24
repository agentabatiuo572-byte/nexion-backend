-- One-time correction for the exchange mission omitted from the H3 business pause.
-- The runtime gate follows /api/config/exchange/caps as the switch changes later.
START TRANSACTION;
INSERT IGNORE INTO nx_admin_operation_mutex(lock_key,updated_at) VALUES('H3_CONFIG',NOW());
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H3_CONFIG' FOR UPDATE;
-- Serialize with in-flight swaps before scanning their committed outbox facts.
INSERT IGNORE INTO nx_admin_operation_mutex(lock_key,updated_at) VALUES('G2_EXCHANGE_EXECUTION',NOW());
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='G2_EXCHANGE_EXECUTION' FOR UPDATE;
SET @h3_exchange_rollout_at := NOW(3);
SET @h3_exchange_pause_at := NOW();

INSERT IGNORE INTO nx_growth_mission_business_gate_rollout(mission_code,first_applied_at)
SELECT m.mission_code,@h3_exchange_rollout_at
  FROM nx_mission m
 WHERE m.mission_code='weekly_t2_nex_swap' AND m.mission_type='WEEKLY_T2' AND m.is_deleted=0
   -- Keep the definition active until already-earned exchange facts finish the
   -- source evaluator and quest projector. Both consumers require status=1.
   AND NOT EXISTS (
     SELECT 1 FROM nx_event_outbox o
       LEFT JOIN nx_event_consumer_delivery d
         ON d.event_id=o.event_id AND d.consumer_group=CASE o.event_type
              WHEN 'exchange.swapped' THEN 'h3-weekly-exchange-referral-evaluator'
              ELSE 'h3-quest-completion' END AND d.is_deleted=0
      WHERE o.event_type IN ('exchange.swapped','H3_EXCHANGE_COMPLETED')
        AND o.is_server_authoritative=1 AND o.is_deleted=0
        AND DATE_FORMAT(o.event_ts,'%x-W%v')=
            DATE_FORMAT(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'),'%x-W%v')
        AND (d.id IS NULL OR d.status NOT IN ('SUCCESS','SKIPPED')))
   AND LOWER(TRIM(COALESCE(
     (SELECT setting_value FROM nx_emergency_control_setting
       WHERE setting_key='killswitch.exchange' AND is_deleted=0 LIMIT 1),
     (SELECT setting_value FROM nx_emergency_control_setting
       WHERE setting_key='emergency.killswitch.exchange' AND is_deleted=0 LIMIT 1),'')))
       NOT IN ('enabled','enable','on','true','1');

INSERT INTO nx_growth_mission_gate_pause_receipt
  (mission_id,mission_code,reason,previous_status,paused_at)
SELECT m.id,m.mission_code,'BUSINESS_OR_COMPLETION_UNAVAILABLE',m.status,@h3_exchange_pause_at
  FROM nx_mission m
  JOIN nx_growth_mission_business_gate_rollout r ON r.mission_code=m.mission_code
 WHERE r.first_applied_at=@h3_exchange_rollout_at AND m.status=1 AND m.is_deleted=0;
UPDATE nx_mission m
JOIN nx_growth_mission_business_gate_rollout r ON r.mission_code=m.mission_code
   SET m.status=0,m.updated_at=@h3_exchange_pause_at
 WHERE r.first_applied_at=@h3_exchange_rollout_at AND m.status=1 AND m.is_deleted=0;
COMMIT;
