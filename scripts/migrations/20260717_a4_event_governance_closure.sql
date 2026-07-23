-- A4 closure: real Schema Registry, structured domain extensions and one event fact source.

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_a4_outbox_columns;
DELIMITER //
CREATE PROCEDURE add_a4_outbox_columns()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='event_name') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN event_name VARCHAR(128) NULL AFTER event_type;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='family_key') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN family_key VARCHAR(32) NULL AFTER event_name;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='event_ts') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN event_ts DATETIME(3) NULL AFTER family_key;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='phase') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN phase VARCHAR(32) NULL AFTER event_ts;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='account_age_months') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN account_age_months INT NOT NULL DEFAULT 0 AFTER phase;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='cohort') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN cohort VARCHAR(16) NULL AFTER account_age_months;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='is_server_authoritative') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN is_server_authoritative TINYINT NOT NULL DEFAULT 0 AFTER cohort;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='schema_revision') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN schema_revision INT NULL AFTER is_server_authoritative;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='schema_registered') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN schema_registered TINYINT NOT NULL DEFAULT 0 AFTER schema_revision;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND column_name='analytics_event') THEN
    ALTER TABLE nx_event_outbox ADD COLUMN analytics_event TINYINT NOT NULL DEFAULT 0 AFTER schema_registered;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND index_name='idx_event_outbox_analytics_time') THEN
    ALTER TABLE nx_event_outbox ADD KEY idx_event_outbox_analytics_time (analytics_event, schema_registered, event_ts);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_event_outbox' AND index_name='idx_event_outbox_family_time') THEN
    ALTER TABLE nx_event_outbox ADD KEY idx_event_outbox_family_time (family_key, event_ts);
  END IF;
END//
DELIMITER ;
CALL add_a4_outbox_columns();
DROP PROCEDURE add_a4_outbox_columns;

CREATE TABLE IF NOT EXISTS nx_event_schema_revision (
  id TINYINT PRIMARY KEY,
  current_revision INT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_event_schema_registry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_name VARCHAR(128) NOT NULL,
  owner_domain VARCHAR(32) NOT NULL,
  family_key VARCHAR(32) NOT NULL,
  producer VARCHAR(32) NOT NULL,
  consumers VARCHAR(255) NOT NULL DEFAULT '',
  is_server_authoritative TINYINT NOT NULL,
  sampling_policy VARCHAR(96) NOT NULL,
  current_revision INT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by VARCHAR(128) NOT NULL,
  updated_by VARCHAR(128) NULL,
  reason VARCHAR(200) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_event_schema_name (event_name),
  KEY idx_event_schema_domain_status (owner_domain, status, is_deleted),
  KEY idx_event_schema_family_status (family_key, status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_event_schema_property (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  schema_id BIGINT NOT NULL,
  property_name VARCHAR(64) NOT NULL,
  property_type VARCHAR(32) NOT NULL,
  pii TINYINT NOT NULL DEFAULT 0,
  required_field TINYINT NOT NULL DEFAULT 1,
  registry_revision INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_event_schema_property (schema_id, property_name),
  KEY idx_event_schema_property_revision (registry_revision),
  CONSTRAINT fk_event_schema_property_schema FOREIGN KEY (schema_id) REFERENCES nx_event_schema_registry(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_event_domain_extension (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  domain_name VARCHAR(32) NOT NULL,
  event_name VARCHAR(128) NOT NULL,
  producer VARCHAR(128) NOT NULL,
  consumer VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'REGISTERED',
  created_by VARCHAR(128) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_event_domain_extension (domain_name, event_name),
  KEY idx_event_domain_extension_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('admin.tamper_config_changed', 'admin', 'phase_admin', 'server', 'A2/J3', 1, '100%', 1, 'ACTIVE', 'migration:a4', 'Seed live J3 event before enforcing the registry gate', 0),
  ('admin.killswitch_toggled', 'admin', 'phase_admin', 'server', 'A2/B5/J1/J2', 1, '100%', 2, 'ACTIVE', 'migration:a4', 'Seed live kill-switch event before enforcing the registry gate', 0),
  ('admin.geo_policy_changed', 'admin', 'phase_admin', 'server', 'A2/J2', 1, '100%', 3, 'ACTIVE', 'migration:a4', 'Seed live J2 event before enforcing the registry gate', 0),
  ('risk.tamper_detected', 'risk', 'risk', 'server', 'K4/J3', 1, '100%', 4, 'ACTIVE', 'migration:a4', 'Seed live tamper event before enforcing the registry gate', 0),
  ('risk.multi_account_flagged', 'risk', 'risk', 'server', 'K1/K4', 1, '100%', 5, 'ACTIVE', 'migration:a4', 'Seed live K1 event before enforcing the registry gate', 0),
  ('risk.multi_account_incident_created', 'risk', 'risk', 'server', 'K1/K4', 1, '100%', 6, 'ACTIVE', 'migration:a4', 'Seed live K1 incident event before enforcing the registry gate', 0),
  ('auth.register_completed', 'auth', 'acquisition', 'server', 'L1/L2 BI', 1, '100%', 7, 'ACTIVE', 'migration:a4', 'Bootstrap the canonical registration event for product-service publishers', 0),
  ('checkout.completed', 'checkout', 'conversion', 'server', 'L1/L2 BI', 1, '100%', 8, 'ACTIVE', 'migration:a4', 'Bootstrap the canonical checkout event for product-service publishers', 0),
  ('withdraw.submitted', 'withdraw', 'monetization', 'server', 'L1/L2 BI', 1, '100%', 9, 'ACTIVE', 'migration:a4', 'Bootstrap the canonical withdrawal event for product-service publishers', 0),
  ('wallet.topup_confirmed', 'wallet', 'monetization', 'server', 'L1/L2 BI', 1, '100%', 10, 'ACTIVE', 'migration:a4', 'Bootstrap the canonical wallet event for product-service publishers', 0),
  ('checkout.started', 'checkout', 'conversion', 'server', 'L1/L2 BI', 1, '100%', 11, 'ACTIVE', 'migration:a4', 'Register the real app order-creation producer before publishing', 0)
ON DUPLICATE KEY UPDATE status='ACTIVE', is_deleted=0;

DELETE p FROM nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
 WHERE p.property_name='resource_id'
   AND s.event_name IN (
     'admin.tamper_config_changed', 'admin.killswitch_toggled', 'admin.geo_policy_changed',
     'risk.tamper_detected', 'risk.multi_account_flagged', 'risk.multi_account_incident_created');

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, p.required_field, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'admin.tamper_config_changed' event_name, 'before' property_name, 'json' property_type, 1 required_field UNION ALL
    SELECT 'admin.tamper_config_changed', 'after', 'json', 1 UNION ALL
    SELECT 'admin.tamper_config_changed', 'audience_role', 'enum', 1 UNION ALL
    SELECT 'admin.tamper_config_changed', 'operator', 'id', 1 UNION ALL
    SELECT 'admin.tamper_config_changed', 'reason', 'string', 1 UNION ALL
    SELECT 'admin.tamper_config_changed', 'occurred_at', 'timestamp', 1 UNION ALL
    SELECT 'admin.killswitch_toggled', 'before', 'json', 1 UNION ALL
    SELECT 'admin.killswitch_toggled', 'after', 'json', 1 UNION ALL
    SELECT 'admin.killswitch_toggled', 'operator', 'id', 1 UNION ALL
    SELECT 'admin.killswitch_toggled', 'reason', 'string', 1 UNION ALL
    SELECT 'admin.killswitch_toggled', 'occurred_at', 'timestamp', 1 UNION ALL
    SELECT 'admin.killswitch_toggled', 'switch_key', 'id', 0 UNION ALL
    SELECT 'admin.killswitch_toggled', 'trigger', 'enum', 0 UNION ALL
    SELECT 'admin.killswitch_toggled', 'key', 'id', 0 UNION ALL
    SELECT 'admin.killswitch_toggled', 'scope', 'enum', 0 UNION ALL
    SELECT 'admin.killswitch_toggled', 'target', 'id', 0 UNION ALL
    SELECT 'admin.killswitch_toggled', 'trigger_basis', 'string', 0 UNION ALL
    SELECT 'admin.killswitch_toggled', 'audience_role', 'enum', 0 UNION ALL
    SELECT 'admin.geo_policy_changed', 'key', 'id', 1 UNION ALL
    SELECT 'admin.geo_policy_changed', 'before', 'json', 1 UNION ALL
    SELECT 'admin.geo_policy_changed', 'after', 'json', 1 UNION ALL
    SELECT 'admin.geo_policy_changed', 'operator', 'id', 1 UNION ALL
    SELECT 'admin.geo_policy_changed', 'reason', 'string', 1 UNION ALL
    SELECT 'admin.geo_policy_changed', 'occurred_at', 'timestamp', 1 UNION ALL
    SELECT 'risk.tamper_detected', 'user_id', 'id', 1 UNION ALL
    SELECT 'risk.tamper_detected', 'user_no', 'id', 0 UNION ALL
    SELECT 'risk.tamper_detected', 'tamper_path', 'enum', 1 UNION ALL
    SELECT 'risk.tamper_detected', 'attack_effect', 'string', 1 UNION ALL
    SELECT 'risk.tamper_detected', 'blocked_at_endpoint', 'string', 1 UNION ALL
    SELECT 'risk.tamper_detected', 'event_count', 'number', 1 UNION ALL
    SELECT 'risk.tamper_detected', 'occurred_at', 'timestamp', 1 UNION ALL
    SELECT 'risk.multi_account_flagged', 'cluster_id', 'id', 1 UNION ALL
    SELECT 'risk.multi_account_flagged', 'strength', 'number', 1 UNION ALL
    SELECT 'risk.multi_account_flagged', 'suggested_action', 'enum', 1 UNION ALL
    SELECT 'risk.multi_account_flagged', 'affected_user_ids', 'json', 1 UNION ALL
    SELECT 'risk.multi_account_flagged', 'dedup_layer', 'enum', 1 UNION ALL
    SELECT 'risk.multi_account_flagged', 'score', 'number', 1 UNION ALL
    SELECT 'risk.multi_account_flagged', 'linked_count', 'number', 1 UNION ALL
    SELECT 'risk.multi_account_flagged', 'ts', 'timestamp', 1 UNION ALL
    SELECT 'risk.multi_account_incident_created', 'cluster_id', 'id', 1 UNION ALL
    SELECT 'risk.multi_account_incident_created', 'lineage_root_cluster_id', 'id', 1 UNION ALL
    SELECT 'risk.multi_account_incident_created', 'previous_terminal_status', 'enum', 1 UNION ALL
    SELECT 'risk.multi_account_incident_created', 'strength', 'number', 1 UNION ALL
    SELECT 'risk.multi_account_incident_created', 'affected_user_ids', 'json', 1 UNION ALL
    SELECT 'auth.register_completed', 'user_id', 'id', 1 UNION ALL
    SELECT 'checkout.completed', 'order_id', 'id', 1 UNION ALL
    SELECT 'withdraw.submitted', 'withdrawal_id', 'id', 1 UNION ALL
    SELECT 'wallet.topup_confirmed', 'transaction_id', 'id', 1
    UNION ALL SELECT 'checkout.started', 'user_id', 'id', 1
    UNION ALL SELECT 'checkout.started', 'order_id', 'id', 1
    UNION ALL SELECT 'checkout.started', 'product_id', 'id', 1
    UNION ALL SELECT 'checkout.started', 'quantity', 'number', 1
    UNION ALL SELECT 'checkout.started', 'amount_usdt', 'number', 1
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision)
SELECT 1, GREATEST(1, COALESCE(MAX(current_revision), 1)) FROM nx_event_schema_registry
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision, VALUES(current_revision));

UPDATE nx_event_outbox
   SET event_name = CASE event_type
       WHEN 'ADMIN_J3_TAMPER_CONFIG_CHANGED' THEN 'admin.tamper_config_changed'
       WHEN 'ADMIN_KILLSWITCH_TOGGLED' THEN 'admin.killswitch_toggled'
       WHEN 'J1_KILLSWITCH_CHANGED' THEN 'admin.killswitch_toggled'
       WHEN 'J2_GEO_POLICY_CHANGED' THEN 'admin.geo_policy_changed'
       WHEN 'RISK_TAMPER_DETECTED' THEN 'risk.tamper_detected'
       WHEN 'RISK_MULTI_ACCOUNT_FLAGGED' THEN 'risk.multi_account_flagged'
       WHEN 'RISK_MULTI_ACCOUNT_INCIDENT_CREATED' THEN 'risk.multi_account_incident_created'
       ELSE event_name END,
       event_ts = COALESCE(event_ts, created_at),
       phase = COALESCE(NULLIF(phase, ''), 'SYSTEM'),
       cohort = COALESCE(NULLIF(cohort, ''), DATE_FORMAT(created_at, '%x-W%v')),
       account_age_months = COALESCE(account_age_months, 0),
       is_server_authoritative = 1
 WHERE is_deleted=0;

UPDATE nx_event_outbox o
JOIN nx_event_schema_registry s ON s.event_name=o.event_name AND s.status='ACTIVE' AND s.is_deleted=0
   SET o.family_key=s.family_key,
       o.schema_revision=s.current_revision,
       o.schema_registered=1,
       o.analytics_event=1,
       o.payload=JSON_SET(
         o.payload,
         '$.event_id', o.event_id,
         '$.event_name', o.event_name,
         '$.ts', CAST(UNIX_TIMESTAMP(o.event_ts) * 1000 AS UNSIGNED),
         '$.user_id', COALESCE(JSON_EXTRACT(o.payload, '$.user_id'), JSON_EXTRACT('null', '$')),
         '$.anon_id', COALESCE(JSON_EXTRACT(o.payload, '$.anon_id'), JSON_EXTRACT('null', '$')),
         '$.session_id', COALESCE(JSON_EXTRACT(o.payload, '$.session_id'), JSON_EXTRACT('null', '$')),
         '$.phase', o.phase,
         '$.account_age_months', o.account_age_months,
         '$.cohort', o.cohort,
         '$.ref', COALESCE(JSON_EXTRACT(o.payload, '$.ref'), JSON_EXTRACT('null', '$')),
         '$.source', COALESCE(JSON_EXTRACT(o.payload, '$.source'), JSON_EXTRACT('null', '$')),
         '$.platform', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.platform')), 'server'),
         '$.app_version', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.app_version')), 'backend'),
         '$.locale', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.locale')), 'und'),
         '$.is_server_authoritative', TRUE,
         '$.schema_revision', s.current_revision)
 WHERE o.is_deleted=0;

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('admin.a4.event.kpi.day0', '90 秒', 'STRING', 'admin_a4_event', 'ADMIN', 'A4 PRD default', 1, 0),
  ('admin.a4.event.kpi.retention', 'D1·D7·D30', 'STRING', 'admin_a4_event', 'ADMIN', 'A4 locked PRD default', 1, 0),
  ('admin.a4.event.kpi.event_retention', '13 个月', 'STRING', 'admin_a4_event', 'ADMIN', 'A4 PRD minimum', 1, 0),
  ('admin.a4.event.kpi.sampling', '浏览/会话 10% · 资金/风控/转化 100%', 'STRING', 'admin_a4_event', 'ADMIN', 'A4 protected sampling policy', 1, 0)
ON DUPLICATE KEY UPDATE value_type='STRING', config_group='admin_a4_event', visibility='ADMIN', status=1, is_deleted=0;
