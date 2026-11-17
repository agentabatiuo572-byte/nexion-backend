-- Restore the canonical H4 wheel award registration omitted by the DDL-only fresh install.
-- Definition: 20260722_h_engagement_execution.sql; lifecycle: 20260810_ab_pending_closure.sql.
START TRANSACTION;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
SELECT 'event.spin_awarded', 'event', 'engagement', 'AppGrowthWheelService', 'A4/H4/D4', 1,
       '100%', 107, 'ACTIVE', 'migration:h4-wheel-award-registry', 'Restore H4 wheel reward result registration', 0
  FROM nx_event_schema_revision
 WHERE id = 1
   AND NOT EXISTS (SELECT 1 FROM nx_event_schema_registry WHERE event_name = 'event.spin_awarded')
   AND NOT EXISTS (SELECT 1 FROM nx_admin_event_lifecycle WHERE event_name = 'event.spin_awarded')
   AND NOT EXISTS (SELECT 1 FROM nx_event_domain_extension WHERE event_name = 'event.spin_awarded');

SET @h4_wheel_schema_id = IF(ROW_COUNT() = 1, LAST_INSERT_ID(), NULL);

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT @h4_wheel_schema_id, p.property_name, p.property_type, 0, 1, 107, 0
  FROM (
    SELECT 'campaign_id' AS property_name, 'id' AS property_type UNION ALL
    SELECT 'tier_id', 'id' UNION ALL
    SELECT 'reward_type', 'enum' UNION ALL
    SELECT 'reward_amount', 'number' UNION ALL
    SELECT 'downgraded', 'boolean' UNION ALL
    SELECT 'source_type', 'enum'
  ) p
 WHERE @h4_wheel_schema_id IS NOT NULL;

INSERT INTO nx_admin_event_lifecycle
  (event_name, lifecycle_state, version, changed_by, reason, is_deleted)
SELECT 'event.spin_awarded', 'full', 0, 'migration:h4-wheel-award-registry',
       'Restore lifecycle for the existing H4 wheel event', 0
 WHERE @h4_wheel_schema_id IS NOT NULL;

INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
SELECT 'event', 'event.spin_awarded', 'AppGrowthWheelService', 'A4/H4/D4', 'REGISTERED',
       'migration:h4-wheel-award-registry', 'Restore H4 wheel reward registration', 0
 WHERE @h4_wheel_schema_id IS NOT NULL;

UPDATE nx_event_schema_revision
   SET current_revision = 107
 WHERE id = 1 AND current_revision < 107 AND @h4_wheel_schema_id IS NOT NULL;

COMMIT;
