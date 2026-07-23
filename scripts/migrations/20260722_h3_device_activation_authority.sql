USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Forward-only registry correction. Existing DEAD outbox deliveries were created
-- under the old non-authoritative contract and intentionally remain untouched.
UPDATE nx_event_schema_registry
   SET is_server_authoritative=1,
       consumers='E5/A2/A4/L3/H3',
       updated_by='migration:h3-trusted-device-activation',
       reason='H3 trusted producer: E5 OpsDeviceService device activation',
       updated_at=NOW()
 WHERE event_name='admin.device_activated'
   AND status='ACTIVE'
   AND is_deleted=0
   AND (is_server_authoritative<>1
        OR consumers<>'E5/A2/A4/L3/H3'
        OR COALESCE(updated_by,'')<>'migration:h3-trusted-device-activation'
        OR reason<>'H3 trusted producer: E5 OpsDeviceService device activation');

UPDATE nx_event_domain_extension
   SET consumer='E5/A2/A4/L3/H3',
       reason='E5 trusted activation contract consumed by H3',
       updated_at=NOW()
 WHERE domain_name='admin'
   AND event_name='admin.device_activated'
   AND status='REGISTERED'
   AND is_deleted=0
   AND (consumer<>'E5/A2/A4/L3/H3'
        OR reason<>'E5 trusted activation contract consumed by H3');
