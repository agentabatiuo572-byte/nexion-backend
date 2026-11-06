-- Older installations can retain these canonical batch fields as soft-deleted rows.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id = p.schema_id AND s.current_revision = p.registry_revision
SET p.is_deleted = 0
WHERE s.event_name = 'admin.exchange_queue_batch_processed'
  AND s.status = 'ACTIVE' AND s.is_deleted = 0 AND p.is_deleted = 1
  AND ((p.property_name = 'failed_count' AND p.property_type = 'number')
    OR (p.property_name = 'outcome' AND p.property_type = 'string'))
  AND p.pii = 0 AND p.required_field = 0;
