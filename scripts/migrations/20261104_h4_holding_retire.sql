-- Product retirement: every holding activity, including previously published rows.
-- Keep quest tombstones so the immutable INSERT IGNORE seed cannot revive them.
-- Startup also runs this before the fund guard, when H4 tables may not exist yet.
DROP TRIGGER IF EXISTS nx_wallet_h4_holding_observation;
DROP TABLE IF EXISTS nx_growth_event_holding;

SET @h4_rules_exist=(SELECT COUNT(*) FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_event_rule');
SET @h4_quests_exist=(SELECT COUNT(*) FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_event_quest');
SET @h4_retire_sql=IF(@h4_rules_exist=1 AND @h4_quests_exist=1,
 'UPDATE nx_growth_event_rule r LEFT JOIN nx_event_quest q ON q.quest_code=r.event_code
  SET r.enabled=0,r.disabled_at=COALESCE(r.disabled_at,UTC_TIMESTAMP(6)),
      r.revision=r.revision+1,r.updated_at=UTC_TIMESTAMP(6)
  WHERE (r.kind=''holding'' OR q.target_type=''holding'')
    AND (r.enabled<>0 OR r.disabled_at IS NULL)',
 IF(@h4_rules_exist=1,
 'UPDATE nx_growth_event_rule SET enabled=0,disabled_at=COALESCE(disabled_at,UTC_TIMESTAMP(6)),
  revision=revision+1,updated_at=UTC_TIMESTAMP(6)
  WHERE kind=''holding'' AND (enabled<>0 OR disabled_at IS NULL)', 'DO 0'));
PREPARE h4_retire_stmt FROM @h4_retire_sql;
EXECUTE h4_retire_stmt;
DEALLOCATE PREPARE h4_retire_stmt;

SET @h4_retire_sql=IF(@h4_quests_exist=1,
 CONCAT('UPDATE nx_event_quest SET status=2,is_deleted=1,updated_at=NOW()
  WHERE (target_type=''holding''',
  IF(@h4_rules_exist=1,' OR quest_code IN (SELECT event_code FROM nx_growth_event_rule WHERE kind=''holding'')',''),
  ') AND (status<>2 OR is_deleted<>1)'), 'DO 0');
PREPARE h4_retire_stmt FROM @h4_retire_sql;
EXECUTE h4_retire_stmt;
DEALLOCATE PREPARE h4_retire_stmt;
