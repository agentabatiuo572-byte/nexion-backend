-- Link readiness to the exact successful drill row. Historical timestamps are
-- deliberately not backfilled: their missing execution evidence must stay visible.
SET @j4_playbook_exists = (
  SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND table_name = 'nx_emergency_sop_playbook'
);
SET @j4_drill_link_exists = (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'nx_emergency_sop_playbook'
     AND column_name = 'last_drill_execution_id'
);
SET @j4_drill_link_sql = IF(
  @j4_playbook_exists > 0 AND @j4_drill_link_exists = 0,
  'ALTER TABLE nx_emergency_sop_playbook ADD COLUMN last_drill_execution_id VARCHAR(96) NULL AFTER last_drill_at',
  'SELECT 1'
);
PREPARE j4_drill_link_stmt FROM @j4_drill_link_sql;
EXECUTE j4_drill_link_stmt;
DEALLOCATE PREPARE j4_drill_link_stmt;
