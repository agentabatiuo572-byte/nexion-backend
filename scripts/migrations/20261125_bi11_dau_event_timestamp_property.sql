SET NAMES utf8mb4;

-- Repair the active DAU property without changing the registry revision or event history.
INSERT INTO nx_event_schema_property
    (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, 'event_ts', 'timestamp', 0, 1, s.current_revision, 0
  FROM nx_event_schema_registry s
 WHERE s.event_name = 'app.dau' AND s.status = 'ACTIVE' AND s.is_deleted = 0
ON DUPLICATE KEY UPDATE property_type = 'timestamp', pii = 0, required_field = 1,
    registry_revision = s.current_revision, is_deleted = 0;
