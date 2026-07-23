-- D5 canonical aggregate withdrawal limits, H1-owned dials and least-privilege RBAC closure.

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('withdrawal.daily_count_limit','2','NUMBER','wallet','ADMIN','D5 daily withdrawal count',1,0),
  ('withdrawal.max_balance_pct','0.80','NUMBER','wallet','ADMIN','D5 withdrawable balance ratio',1,0),
  ('withdrawal.fee_rate','0.02','NUMBER','wallet','ADMIN','D5 network fee ratio',1,0),
  ('withdrawal.fee_min_usdt','0.50','NUMBER','wallet','ADMIN','D5 minimum network fee',1,0),
  ('withdrawal.fee_max_usdt','20.00','NUMBER','wallet','ADMIN','D5 maximum network fee',1,0),
  ('withdrawal.nex_fee_offset_rate','0.40','NUMBER','wallet','ADMIN','D5 NEX fee offset rate',1,0),
  ('withdrawal.d5.version','1','NUMBER','wallet','ADMIN','D5 aggregate config version',1,0),
  ('growth.phase.withdraw_cooldown_days','30','NUMBER','growth','ADMIN','H1 active withdrawal cooldown dial',1,0),
  ('growth.phase.withdraw_penalty_fee_rate','0.20','NUMBER','growth','ADMIN','H1 active withdrawal penalty fee dial',1,0),
  ('growth.phase.compliance_hold_enabled','0','BOOLEAN','growth','ADMIN','H1 active compliance hold dial',1,0)
ON DUPLICATE KEY UPDATE value_type=VALUES(value_type), config_group=VALUES(config_group), visibility='ADMIN',
  remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

-- Canonical values win after this one-time repair; consumers read only the canonical keys.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT CONCAT('wallet.', config_key), config_value, value_type, 'wallet', 'ADMIN',
       CONCAT('D5 mirror of ', config_key), 1, 0
FROM nx_config_item
WHERE config_key IN ('withdrawal.daily_count_limit','withdrawal.max_balance_pct','withdrawal.fee_rate',
                     'withdrawal.fee_min_usdt','withdrawal.fee_max_usdt','withdrawal.nex_fee_offset_rate')
  AND status=1 AND is_deleted=0
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value), value_type=VALUES(value_type),
  config_group='wallet', visibility='ADMIN', remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

-- Current 12-month Phase matrix rows for the three H1-owned withdrawal dials.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT CONCAT('growth.phase.month.', month_no, '.withdrawCooldownDays'),
       CASE WHEN month_no <= 7 THEN '30' WHEN month_no = 8 THEN '35' ELSE '45' END,
       'NUMBER','growth','ADMIN','H1 monthly withdrawal cooldown dial',1,0
FROM (SELECT 1 month_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
      UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12) months
ON DUPLICATE KEY UPDATE value_type='NUMBER',config_group='growth',visibility='ADMIN',status=1,is_deleted=0,updated_at=NOW();

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT CONCAT('growth.phase.month.', month_no, '.withdrawPenaltyFeeRate'),
       CASE WHEN month_no <= 8 THEN '0.20' WHEN month_no <= 10 THEN '0.25' ELSE '0.30' END,
       'NUMBER','growth','ADMIN','H1 monthly withdrawal penalty dial',1,0
FROM (SELECT 1 month_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
      UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12) months
ON DUPLICATE KEY UPDATE value_type='NUMBER',config_group='growth',visibility='ADMIN',status=1,is_deleted=0,updated_at=NOW();

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT CONCAT('growth.phase.month.', month_no, '.complianceHoldEnabled'),
       CASE WHEN month_no >= 8 THEN '1' ELSE '0' END,
       'BOOLEAN','growth','ADMIN','H1 monthly compliance hold dial',1,0
FROM (SELECT 1 month_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
      UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12) months
ON DUPLICATE KEY UPDATE value_type='BOOLEAN',config_group='growth',visibility='ADMIN',status=1,is_deleted=0,updated_at=NOW();

-- D5 read: super admin, finance, finance lead, risk, growth, auditor. D5 writes: lead and super admin only.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'finance_d5_%';

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR')
  AND p.permission_code='finance_d5_read'
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code IN ('finance_d5_daily_limit_write','finance_d5_balance_max_write','finance_d5_fee_write')
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

-- Growth needs D5 visibility; finance/risk need the H1 read destination promised by the D5 page.
INSERT INTO nx_admin_role_menu(role_id,menu_id,is_deleted)
SELECT r.id,m.id,0 FROM nx_admin_role r JOIN nx_admin_menu m
WHERE ((r.role_code='GROWTH' AND m.menu_code IN ('D','D5'))
    OR (r.role_code IN ('FINANCE','FINANCE_LEAD','RISK') AND m.menu_code IN ('H','H1')))
  AND r.status=1 AND r.is_deleted=0 AND m.status=1 AND m.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK') AND p.permission_code='growth_h1_read'
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

