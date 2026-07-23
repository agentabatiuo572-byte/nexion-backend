-- Repair the C4 A4 schema seed to use the validator's canonical textual type.
-- Safe to run repeatedly; only the invalid legacy value is changed.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id = p.schema_id
SET p.property_type = 'string'
WHERE s.event_name IN (
        'admin.kyc_status_changed',
        'risk.kyc_review_triggered',
        'admin.kyc_export_created'
    )
  AND p.property_name = 'evidence_ref'
  AND p.property_type = 'text'
  AND p.is_deleted = 0
  AND s.is_deleted = 0;
