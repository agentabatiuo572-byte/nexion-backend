-- B1 dual-ledger least-privilege repair.
-- Read: finance/risk/growth/auditor. Writes: super admin and finance lead only.

-- PRD B1 defaults: red 100%, yellow 110%. Only migrate the known legacy defaults;
-- preserve any operator-configured value outside those legacy values.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('wallet.dual-ledger.redline-pct','100','NUMBER','wallet','ADMIN','B1 coverage red line, allowed 80-150',1,0),
  ('wallet.dual-ledger.healthy-pct','110','NUMBER','wallet','ADMIN','B1 coverage yellow line, allowed 100-200 and greater than red line',1,0)
ON DUPLICATE KEY UPDATE
  config_value=CASE
    WHEN config_key='wallet.dual-ledger.redline-pct' AND CAST(config_value AS DECIMAL(10,2))=85 THEN '100'
    WHEN config_key='wallet.dual-ledger.healthy-pct' AND CAST(config_value AS DECIMAL(10,2))=100 THEN '110'
    ELSE config_value
  END,
  value_type='NUMBER', config_group='wallet', visibility='ADMIN',
  remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code <> 'SUPER_ADMIN' AND p.permission_code LIKE 'overview_b1_%';

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR')
  AND p.permission_code='overview_b1_read'
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code IN ('overview_b1_write','overview_b1_redline_write')
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;
