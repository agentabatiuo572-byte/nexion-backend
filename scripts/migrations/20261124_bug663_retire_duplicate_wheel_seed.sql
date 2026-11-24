-- Retire only the unused historical placeholder when the operational wheel is present.
-- Keep the quest code, rule row, and any real historical activity or operator work.
SET @bug663_wheel_repair_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.tables
           WHERE table_schema = DATABASE() AND table_name = 'nx_growth_event_rule'),
  'UPDATE nx_event_quest q
     JOIN nx_event_quest live_wheel ON live_wheel.quest_code = ''evt-spring-spin''
          AND live_wheel.target_type = ''wheel'' AND live_wheel.is_deleted = 0
     JOIN nx_growth_event_rule r ON r.event_code = q.quest_code AND r.kind = ''wheel''
      SET q.is_deleted = 1, q.updated_at = CURRENT_TIMESTAMP
    WHERE q.quest_code = ''evt-anniversary-spin''
      AND q.is_deleted = 0 AND q.status = 0 AND q.target_type = ''wheel''
      AND q.quest_name = ''Anniversary Mega Spin''
      AND q.description = ''View your recorded awards from this activity.''
      AND q.reward_name = ''Recorded wheel awards''
      AND q.reward_type IN (''NEX'', ''UVT'') AND q.reward_amount = 0 AND q.target_value = 0
      AND q.starts_at IS NULL AND q.ends_at IS NULL AND q.badge_achievement_code IS NULL
      AND COALESCE(q.geo_scope, '''') = '''' AND COALESCE(q.cta_href, '''') = ''''
      AND r.enabled = 0 AND r.revision = 1 AND r.rules_json = JSON_OBJECT()
      AND r.starts_at IS NULL AND r.ends_at IS NULL AND r.activated_at IS NULL AND r.disabled_at IS NULL
      AND r.spent_nex = 0 AND r.spent_usdt = 0 AND r.spent_devices = 0 AND r.spent_tickets = 0
      AND r.settled_at IS NULL AND r.settlement_hash IS NULL
      AND NOT EXISTS (SELECT 1 FROM nx_growth_wheel_spin s
                       WHERE s.event_id = q.id OR s.event_code = q.quest_code)
      AND NOT EXISTS (SELECT 1 FROM nx_user_event_quest u WHERE u.quest_id = q.id OR u.quest_code = q.quest_code)
      AND NOT EXISTS (SELECT 1 FROM nx_growth_event_claim a WHERE a.event_code = q.quest_code)
      AND NOT EXISTS (
        SELECT 1 FROM nx_config_item c
         WHERE c.config_key = ''growth.content.localized'' AND c.status = 1 AND c.is_deleted = 0
           AND JSON_CONTAINS_PATH(IF(JSON_VALID(c.config_value), c.config_value, ''{}''),
                                  ''one'', ''$.event."evt-anniversary-spin"'')
      )
      AND NOT EXISTS (
        SELECT 1 FROM nx_audit_object_lock l
          JOIN nx_audit_operation_ticket t ON t.operation_id = l.ticket_id
         WHERE l.target_domain = ''H'' AND l.target_id = q.quest_code AND l.is_deleted = 0
           AND t.is_deleted = 0 AND t.status = ''pending''
      )',
  'SELECT 0'
);
PREPARE bug663_wheel_repair FROM @bug663_wheel_repair_sql;
EXECUTE bug663_wheel_repair;
DEALLOCATE PREPARE bug663_wheel_repair;
