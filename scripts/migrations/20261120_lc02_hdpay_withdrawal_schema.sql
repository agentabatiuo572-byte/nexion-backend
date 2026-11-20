-- Restore the bank settlement fields on the current A4 revisions.
-- WithdrawalSettlementEventContract still requires chain evidence on chain settlements.
START TRANSACTION;

UPDATE nx_event_schema_property property
JOIN nx_event_schema_registry event ON event.id = property.schema_id
SET property.required_field = 0
WHERE event.status = 'ACTIVE' AND event.is_deleted = 0
  AND property.is_deleted = 0 AND property.registry_revision = event.current_revision
  AND property.required_field = 1
  AND ((event.event_name = 'withdraw.confirmed' AND property.property_name = 'chain_tx_hash')
    OR (event.event_name = 'withdraw.refunded' AND property.property_name = 'risk_score'));

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT event.id, fields.field_name, fields.field_type, 0, 0, event.current_revision, 0
FROM nx_event_schema_registry event
CROSS JOIN (
  SELECT 'rail' AS field_name, 'string' AS field_type
  UNION ALL SELECT 'provider', 'string'
  UNION ALL SELECT 'provider_order_id', 'id'
  UNION ALL SELECT 'amount_vnd', 'number'
  UNION ALL SELECT 'risk_score_status', 'string'
) fields
LEFT JOIN nx_event_schema_property existing
  ON existing.schema_id = event.id AND existing.property_name = fields.field_name
WHERE event.status = 'ACTIVE' AND event.is_deleted = 0
  AND event.event_name IN ('withdraw.confirmed', 'withdraw.refunded')
  AND (fields.field_name <> 'risk_score_status' OR event.event_name = 'withdraw.refunded')
  AND (existing.id IS NULL OR (
    -- The fresh L3 registry restore marks the previously installed bank fields deleted.
    event.event_name = 'withdraw.confirmed' AND event.current_revision = 288
    AND COALESCE(event.updated_by, event.created_by) = 'migration:l3-withdrawal-confirmed'
    AND existing.is_deleted = 1 AND existing.property_type = fields.field_type
    AND existing.pii = 0 AND existing.required_field = 0
  ))
ON DUPLICATE KEY UPDATE
  registry_revision = event.current_revision,
  is_deleted = 0;

COMMIT;
