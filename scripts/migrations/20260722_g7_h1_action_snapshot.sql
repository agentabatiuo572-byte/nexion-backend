USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- G7 applies the H1 reinvest dial at open time. Register the persisted event
-- snapshot before producers emit it; A4 rejects undeclared payload fields.
UPDATE nx_event_schema_registry
   SET current_revision=98,updated_by='migration:g7-h1-snapshot',
       reason='G7 wallet.reinvest includes H1 action-time multiplier snapshot',updated_at=NOW()
 WHERE event_name='wallet.reinvest' AND is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,x.property_name,'number',0,1,98,0
  FROM nx_event_schema_registry s
  JOIN (
        SELECT 'h1_reinvest_multiplier' property_name
        UNION ALL SELECT 'effective_nurture_multiplier'
       ) x
 WHERE s.event_name='wallet.reinvest' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE property_type='number',pii=0,required_field=1,
  registry_revision=98,is_deleted=0;

-- staking.opened is shared with G1. These fields are registered as optional so
-- ordinary staking producers remain valid while G7 carries the action snapshot.
UPDATE nx_event_schema_registry
   SET current_revision=99,updated_by='migration:g7-h1-snapshot',
       reason='Shared staking.opened optionally carries G7 H1 action-time snapshot',updated_at=NOW()
 WHERE event_name='staking.opened' AND is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,x.property_name,'number',0,0,99,0
  FROM nx_event_schema_registry s
  JOIN (
        SELECT 'h1_reinvest_multiplier' property_name
        UNION ALL SELECT 'effective_nurture_multiplier'
       ) x
 WHERE s.event_name='staking.opened' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE property_type='number',pii=0,required_field=0,
  registry_revision=99,is_deleted=0;

INSERT INTO nx_event_schema_revision(id,current_revision) VALUES(1,99)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,99);
