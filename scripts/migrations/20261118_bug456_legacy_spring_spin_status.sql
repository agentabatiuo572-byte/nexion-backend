-- The old wheel seed published this quest before H4 created its disabled rule.
-- A pristine rule is still at revision 1 and has no activation or closure dates.
-- A matching pending A2 lock protects the quest status captured by the proposal.
-- On a fresh local database the rule table is created after the startup list;
-- that later seed already inserts the quest as upcoming.
SET @spring_spin_repair_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.tables
           WHERE table_schema = DATABASE() AND table_name = 'nx_growth_event_rule'),
  'UPDATE nx_event_quest q
     JOIN nx_growth_event_rule r ON r.event_code = q.quest_code
      SET q.status = 0, q.updated_at = q.updated_at
    WHERE q.quest_code = ''evt-spring-spin''
      AND q.is_deleted = 0 AND q.status = 1
      AND q.starts_at IS NULL AND q.ends_at IS NULL
      AND r.enabled = 0 AND r.revision = 1
      AND r.activated_at IS NULL AND r.disabled_at IS NULL
      AND NOT EXISTS (
        SELECT 1 FROM nx_config_item c
         WHERE c.config_key = ''growth.content.localized'' AND c.status = 1 AND c.is_deleted = 0
           AND JSON_CONTAINS_PATH(IF(JSON_VALID(c.config_value), c.config_value, ''{}''),
                                  ''one'', ''$.event."evt-spring-spin"'')
      )
      AND NOT EXISTS (
        SELECT 1 FROM nx_audit_object_lock l
          JOIN nx_audit_operation_ticket t ON t.operation_id = l.ticket_id
         WHERE l.target_domain = ''H'' AND l.target_type = ''h4_event_rule'' AND l.target_id = q.quest_code
           AND l.is_deleted = 0 AND t.is_deleted = 0 AND t.status = ''pending''
      )',
  'SELECT 0'
);
PREPARE spring_spin_repair FROM @spring_spin_repair_sql;
EXECUTE spring_spin_repair;
DEALLOCATE PREPARE spring_spin_repair;
