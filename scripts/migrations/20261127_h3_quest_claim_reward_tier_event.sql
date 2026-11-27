-- Day One claims emit rewardTierBasis. Keep it optional for other quest claims.
INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,'reward_tier_basis','enum',0,0,s.current_revision,0
  FROM nx_event_schema_registry s
 WHERE s.event_name='quest.claimed' AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=0,
  registry_revision=VALUES(registry_revision),is_deleted=0;
