-- 域 D · 资金与财务权限点（from docs/superpowers/specs/rbac-classic/D.md）
-- 依赖 00-permission-alter.sql。幂等 ON DUPLICATE KEY UPDATE。
--   mysql -uroot -p nexion < scripts/rbac-classic-seed/D.sql

INSERT INTO nx_admin_permission (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted) VALUES
  -- D1 充值对账中心 /finance/recon（按职责拆分）
  ('finance_d1_read',              '充值对账-读',         'API', '/finance/recon',        'READ',  0, 1, 0),
  ('finance_d1_channel_manage',    '充值渠道与费率配置',  'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_config_manage',     '银行卡风控参数配置',  'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_psp_switch',        '主备支付商切换',      'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_reconcile',         '充值差异核销',        'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_bin_manual_lock',   'BIN卡段手动锁定',     'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_bin_lock',          'BIN卡段锁定',         'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_bin_unlock',        'BIN卡段解锁',         'API', '/finance/recon',        'HIGH',  1, 1, 0),
  ('finance_d1_chargeback_refund', '拒付追回',            'API', '/finance/recon',        'HIGH',  1, 1, 0),
  -- D2 提现审核队列 /finance/withdrawals (9)
  ('finance_d2_read',                '提现审核-读',     'API', '/finance/withdrawals', 'READ',  0, 1, 0),
  ('finance_d2_withdrawal_approve',  '提现放行',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_delay',    '提现延迟',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_freeze',   '提现冻结',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_unfreeze', '提现解冻',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_reject',   '提现驳回',        'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_refund',   '提现手动退款',    'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  ('finance_d2_withdrawal_batch',    '提现批量审核',    'API', '/finance/withdrawals', 'HIGH',  1, 1, 0),
  -- D3 资金池水位 /finance/pool
  ('finance_d3_read',                '资金池-读',           'API', '/finance/pool', 'READ',  0, 1, 0),
  ('finance_d3_write',               '资金池-口径调整',     'API', '/finance/pool', 'WRITE', 0, 1, 0),
  ('finance_d3_injection_create',    '储备注入登记',        'API', '/finance/pool', 'HIGH',  1, 1, 0),
  ('finance_d3_export',              '资金池对账导出',      'API', '/finance/pool', 'READ',  0, 1, 0),
  ('finance_d3_redline_pct_write',   '覆盖率红线阈值调整',  'API', '/finance/pool', 'HIGH',  1, 1, 0),
  ('finance_d3_healthy_pct_write',   '覆盖率健康阈值调整',  'API', '/finance/pool', 'HIGH',  1, 1, 0),
  ('finance_d3_runrisk_pct_write',   '挤兑风险阈值调整',    'API', '/finance/pool', 'HIGH',  1, 1, 0),
  -- D4 账本/账单审计 /finance/ledger（只读；余额写入统一走 C3）
  ('finance_d4_read',             '全平台账本审计-读', 'API', '/finance/ledger', 'READ', 0, 1, 0),
  ('finance_d4_user_read',        '单用户账本审计-读', 'API', '/finance/ledger', 'READ', 0, 1, 0),
  ('finance_d4_export',           '脱敏账单导出',       'API', '/finance/ledger', 'READ', 0, 1, 0),
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

-- 清理历史 D4 调账旁路权限，避免通用角色种子重新暴露第二写入口。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='finance_d4_adjustment_create';
UPDATE nx_admin_permission
SET status=0, is_deleted=1, updated_at=NOW()
WHERE permission_code='finance_d4_adjustment_create';
