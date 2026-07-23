-- I3/A4 closure: the persisted notification fact is the delivery authority;
-- read and CTA/swipe receipts are committed with their governed outbox events.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_notification_action_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  notification_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  route VARCHAR(255) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_action_idem (idempotency_key),
  UNIQUE KEY uk_notification_action_business (notification_id,user_id,action),
  KEY idx_notification_action_user (user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('notification.delivered', 'notification', 'notification', 'I3DispatchExecutor', 'A4/I3/B3/L', 1,
   '100%', 164, 'ACTIVE', 'migration:i3-a4', 'Persisted user notification became inbox-visible', 0),
  ('notification.read', 'notification', 'notification', 'AppNotificationService', 'A4/I3/L', 1,
   '100%', 165, 'ACTIVE', 'migration:i3-a4', 'Authenticated owner marked a persisted notification read', 0),
  ('notification.swipe_action_taken', 'notification', 'notification', 'AppNotificationService', 'A4/I3/B3/L', 0,
   '100%', 166, 'ACTIVE', 'migration:i3-a4', 'Authenticated owner selected a server-canonical notification route', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy='100%', current_revision=VALUES(current_revision), status='ACTIVE',
  updated_by='migration:i3-a4', reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name IN (
       'notification.delivered','notification.read','notification.swipe_action_taken')
   AND ((s.event_name='notification.delivered'
         AND p.property_name NOT IN ('campaign_id','notification_id','kind','priority'))
        OR (s.event_name='notification.read'
            AND p.property_name NOT IN ('notification_id','kind','priority'))
        OR (s.event_name='notification.swipe_action_taken'
            AND p.property_name NOT IN ('notification_id','kind','action','route')));

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'notification.delivered' event_name, 'campaign_id' property_name, 'id' property_type UNION ALL
    SELECT 'notification.delivered', 'notification_id', 'id' UNION ALL
    SELECT 'notification.delivered', 'kind', 'enum' UNION ALL
    SELECT 'notification.delivered', 'priority', 'enum' UNION ALL
    SELECT 'notification.read', 'notification_id', 'id' UNION ALL
    SELECT 'notification.read', 'kind', 'enum' UNION ALL
    SELECT 'notification.read', 'priority', 'enum' UNION ALL
    SELECT 'notification.swipe_action_taken', 'notification_id', 'id' UNION ALL
    SELECT 'notification.swipe_action_taken', 'kind', 'enum' UNION ALL
    SELECT 'notification.swipe_action_taken', 'action', 'enum' UNION ALL
    SELECT 'notification.swipe_action_taken', 'route', 'string'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
VALUES
  ('notification', 'notification.delivered', 'I3DispatchExecutor', 'A4/I3/B3/L',
   'DONE', 'migration:i3-a4', 'I3 persisted inbox delivery', 0),
  ('notification', 'notification.read', 'AppNotificationService', 'A4/I3/L',
   'DONE', 'migration:i3-a4', 'I3 server-canonical read receipt', 0),
  ('notification', 'notification.swipe_action_taken', 'AppNotificationService', 'A4/I3/B3/L',
   'DONE', 'migration:i3-a4', 'I3 idempotent CTA and swipe action receipt', 0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer), consumer=VALUES(consumer), status='DONE',
  reason=VALUES(reason), is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,166)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,166);
