-- 域 D · 资金与财务 · 23 权限点（from docs/superpowers/specs/rbac-classic/D.md）
-- 依赖 00-permission-alter.sql。幂等 ON DUPLICATE KEY UPDATE。
--   mysql -uroot -p nexion < scripts/rbac-classic-seed/D.sql

INSERT INTO nx_admin_permission (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted) VALUES
  -- D1 充值对账中心 /finance/recon (6)
  ('finance_d1_read',              '充值对账-读',         'API', '/finance/recon',        'READ',  0, 1, 0),
  ('finance_d1_write',             '充值对账-常规写',     'API', '/finance/recon',        'WRITE', 0, 1, 0),
  ('finance_d1_bin_manual_lock',   'BIN卡段手动锁定',     'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_bin_lock',          'BIN卡段锁定',         'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_bin_unlock',        'BIN卡段解锁',         'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_chargeback_refund', '拒付追回',            'API', '/finance/recon',        'HIGH',  1, 1, 0),
  -- D2 提现审核队列 /finance/withdrawals (5)
  ('finance_d2_read',                '提现审核-读',     'API', '/finance/withdrawals', 'READ',  0, 1, 0),
  ('finance_d2_withdrawal_approve',  '提现放行',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_freeze',   '提现冻结',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_unfreeze', '提现解冻',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_reject',   '提现驳回',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  -- D3 资金池水位 /finance/pool (6)
  ('finance_d3_read',                '资金池-读',           'API', '/finance/pool', 'READ',  0, 1, 0),
  ('finance_d3_write',               '资金池-口径调整',     'API', '/finance/pool', 'WRITE', 0, 1, 0),
  ('finance_d3_injection_create',    '储备注入登记',        'API', '/finance/pool', 'HIGH',  1, 1, 0),
  ('finance_d3_redline_pct_write',   '覆盖率红线阈值调整',  'API', '/finance/pool', 'HIGH',  1, 1, 0),
  ('finance_d3_healthy_pct_write',   '覆盖率健康阈值调整',  'API', '/finance/pool', 'HIGH',  1, 1, 0),
  ('finance_d3_runrisk_pct_write',   '挤兑风险阈值调整',    'API', '/finance/pool', 'HIGH',  1, 1, 0),
  -- D4 账本/账单审计 /finance/ledger (2)
  ('finance_d4_read',             '账本审计-读',   'API', '/finance/ledger', 'READ', 0, 1, 0),
  ('finance_d4_adjustment_create','手动调账',      'API', '/finance/ledger', 'HIGH', 1, 1, 0),
  -- D5 提现参数配置 /finance/params (4)
  ('finance_d5_read',              '提现参数-读',        'API', '/finance/params', 'READ', 0, 1, 0),
  ('finance_d5_daily_limit_write', '每日提现次数调整',   'API', '/finance/params', 'HIGH', 1, 1, 0),
  ('finance_d5_balance_max_write', '余额可提上限调整',   'API', '/finance/params', 'HIGH', 1, 1, 0),
  ('finance_d5_fee_write',         '提现费率调整',       'API', '/finance/params', 'HIGH', 1, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type   = VALUES(resource_type),
  resource_path   = VALUES(resource_path),
  perm_type       = VALUES(perm_type),
  amplifies       = VALUES(amplifies),
  status          = 1,
  is_deleted      = 0;
