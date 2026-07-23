-- D3 canonical reserve/liability/forecast closure and least-privilege RBAC.

CREATE TABLE IF NOT EXISTS nx_treasury_legacy_lock_liability (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  liability_no VARCHAR(64) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  principal_usdt DECIMAL(24,6) NOT NULL DEFAULT 0,
  accrued_interest_usdt DECIMAL(24,6) NOT NULL DEFAULT 0,
  maturity_at DATETIME NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  source_note VARCHAR(512) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_d3_legacy_lock_liability_no (liability_no),
  UNIQUE KEY uk_d3_legacy_lock_source_ref (source_ref),
  KEY idx_d3_legacy_lock_status_maturity (status, maturity_at, is_deleted),
  CONSTRAINT chk_d3_legacy_lock_amount CHECK (principal_usdt >= 0 AND accrued_interest_usdt >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Product-approved opening snapshot for the real legacy lock stock (PRD D3 category #8).
-- It is deliberately isolated from wallet available balances and can be reconciled by source_ref.
INSERT IGNORE INTO nx_treasury_legacy_lock_liability
  (liability_no, source_ref, principal_usdt, accrued_interest_usdt, maturity_at, status, source_note, is_deleted)
VALUES
  ('D3-LEGACY-OPENING-20260720','prd:d3:legacy-lock-opening',250000.000000,0.000000,NULL,'ACTIVE',
   'Opening snapshot for sunset legacy NEX staking principal and interest; replace through reconciled ledger rows, never wallet available balance',0);

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('treasury.d3.forecast-config',
   '{"reserveCategories":{"usdt":true,"otherLiquid":true},"liabilityCategories":{"withdrawable_balance":true,"usdt_staking_principal":true,"staking_interest":true,"genesis_daily_emission":true,"nex_v2_future":true,"withdrawal_queue":true,"commission_cooling":true,"lock_other":true},"forecastWindow":"7d","genesisIncluded":true,"includeFarLiabilities":false,"stakingInterestMode":"LINEAR","trialStressEnabled":false}',
   'JSON','treasury','ADMIN','D3 structured forecast configuration',1,0),
  ('treasury.d3.forecast-config.version','1','NUMBER','treasury','ADMIN','D3 forecast configuration version',1,0),
  ('treasury.d3.forecast-config.active-version','1','NUMBER','treasury','ADMIN','D3 active forecast configuration version',1,0),
  ('treasury.d3.forecast-config.pending','','JSON','treasury','ADMIN','D3 pending structured forecast configuration',1,0),
  ('treasury.d3.forecast-config.pending-effective-at','','STRING','treasury','ADMIN','D3 pending forecast configuration UTC effective time',1,0),
  ('treasury.d3.forecast-config.pending-version','','NUMBER','treasury','ADMIN','D3 pending forecast configuration version',1,0)
ON DUPLICATE KEY UPDATE value_type=VALUES(value_type), config_group='treasury', visibility='ADMIN',
  remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('finance_d3_export','资金池对账导出','API','/finance/pool','READ',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), resource_type='API',
  resource_path='/finance/pool', perm_type='READ', amplifies=0, status=1, is_deleted=0, updated_at=NOW();

-- D3 read: super admin, finance, finance lead, risk, growth and auditor.
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR')
  AND p.permission_code='finance_d3_read' AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

-- Export excludes growth. High-sensitivity config and injection are lead/super-admin only.
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d3_export' AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code IN ('finance_d3_write','finance_d3_injection_create')
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code IN ('finance_d3_write','finance_d3_injection_create')
  AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE_LEAD');

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='finance_d3_export'
  AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR');

-- These three old permissions edited B1/B5 coverage thresholds from the D3 page.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code IN ('finance_d3_redline_pct_write','finance_d3_healthy_pct_write','finance_d3_runrisk_pct_write');
UPDATE nx_admin_permission SET status=0,is_deleted=1,updated_at=NOW()
WHERE permission_code IN ('finance_d3_redline_pct_write','finance_d3_healthy_pct_write','finance_d3_runrisk_pct_write');

INSERT IGNORE INTO nx_admin_role_menu(role_id,menu_id)
SELECT r.id,m.id FROM nx_admin_role r JOIN nx_admin_menu m
WHERE r.role_code IN ('FINANCE_LEAD','GROWTH','AUDITOR') AND m.menu_code IN ('D','D3')
  AND r.status=1 AND r.is_deleted=0 AND m.status=1 AND m.is_deleted=0;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('admin.treasury_reserve_injected','admin','money','server','D3/B1/B2/B5/L3',1,'100%',1,'ACTIVE','migration:d3','D3 authoritative reserve injection',0),
  ('admin.treasury_forecast_config_changed','admin','money','server','D3/B1/B2/B5/L3',1,'100%',1,'ACTIVE','migration:d3','D3 structured forecast configuration changed',0)
ON DUPLICATE KEY UPDATE family_key='money',current_revision=1,status='ACTIVE',is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, created_at, updated_at, is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,NOW(),NOW(),0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'voucher_no' property_name,'string' property_type UNION ALL
  SELECT 'amount_usdt','number' UNION ALL
  SELECT 'operator','string' UNION ALL
  SELECT 'reason','string'
) p
WHERE s.event_name='admin.treasury_reserve_injected'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, created_at, updated_at, is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,NOW(),NOW(),0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'operator' property_name,'string' property_type UNION ALL
  SELECT 'reason','string' UNION ALL
  SELECT 'version','number' UNION ALL
  SELECT 'effective_at','timestamp'
) p
WHERE s.event_name='admin.treasury_forecast_config_changed'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0,updated_at=NOW();
