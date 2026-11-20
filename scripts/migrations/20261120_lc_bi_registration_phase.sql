SET NAMES utf8mb4;

-- Keep the registered event's SYSTEM envelope and record the H1 phase at birth.
INSERT INTO nx_event_schema_property
    (schema_id, property_name, property_type, pii, required_field, registry_revision,
     created_at, updated_at, is_deleted)
SELECT id, 'registration_phase', 'enum', 0, 0, current_revision, NOW(), NOW(), 0
  FROM nx_event_schema_registry
 WHERE event_name='auth.register_completed' AND is_deleted=0
ON DUPLICATE KEY UPDATE schema_id=nx_event_schema_property.schema_id;
