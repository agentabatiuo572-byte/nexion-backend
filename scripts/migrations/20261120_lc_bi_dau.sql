SET NAMES utf8mb4;

-- Old activity rows remain B32 facts. Only a new authenticated request can
-- mark today's row and create its A4 event.
SET @dau_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_daily_active' AND COLUMN_NAME='dau_recorded'
), 'SELECT 1', 'ALTER TABLE nx_user_daily_active ADD COLUMN dau_recorded TINYINT(1) NOT NULL DEFAULT 0');
PREPARE dau_stmt FROM @dau_sql;
EXECUTE dau_stmt;
DEALLOCATE PREPARE dau_stmt;

INSERT INTO nx_event_schema_property
    (schema_id, property_name, property_type, pii, required_field, registry_revision,
     created_at, updated_at, is_deleted)
SELECT id, 'event_ts', 'timestamp', 0, 1, current_revision, NOW(), NOW(), 0
  FROM nx_event_schema_registry
 WHERE event_name='app.dau' AND is_deleted=0
ON DUPLICATE KEY UPDATE schema_id=nx_event_schema_property.schema_id;
