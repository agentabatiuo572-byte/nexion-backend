-- L1 revision 285 extended checkout.completed without advancing its original order_id.
-- Repair only the canonical A4/L1 contract; preserve custom, deleted and newer schemas.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.registry_revision=285,p.updated_at=CURRENT_TIMESTAMP
 WHERE BINARY s.event_name=BINARY 'checkout.completed'
   AND BINARY s.owner_domain=BINARY 'checkout'
   AND BINARY s.family_key=BINARY 'conversion'
   AND BINARY s.producer=BINARY 'server'
   AND BINARY s.consumers=BINARY 'A4/F1/F2/B3/L1/L2/L4'
   AND s.is_server_authoritative=1 AND BINARY s.sampling_policy=BINARY '100%'
   AND s.current_revision=285 AND BINARY s.status=BINARY 'ACTIVE' AND s.is_deleted=0
   AND BINARY p.property_name=BINARY 'order_id' AND BINARY p.property_type=BINARY 'id'
   AND p.required_field=1 AND p.pii=0 AND p.registry_revision=8 AND p.is_deleted=0;
