SET NAMES utf8mb4;

-- Allow the risk producer to include the server's confirmed rejection detail.
INSERT INTO nx_event_schema_property
    (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, 'server_finding', 'enum', 0, 0, s.current_revision, 0
  FROM nx_event_schema_registry s
 WHERE s.event_name = 'risk.tamper_detected' AND s.status = 'ACTIVE' AND s.is_deleted = 0
ON DUPLICATE KEY UPDATE property_type = 'enum', pii = 0, required_field = 0,
    registry_revision = s.current_revision, is_deleted = 0;
