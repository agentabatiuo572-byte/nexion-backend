-- 域 C · 用户与账户 · 39 权限点
-- 源：docs/superpowers/specs/rbac-classic/C.md（6 页 + C1 详情 HUB · 原 72 点收敛后 39 点 · READ 7 · WRITE 7 · HIGH 25）
-- 表：nx_admin_permission（schema 见 00-permission-alter.sql）
-- 幂等：permission_code 唯一键，重复执行只更新不报错。
-- amplifies=1 的 6 个（A 类资金流出放大，名字标 ⚡资金放大）：
--   user_c1hub_earning_grant / user_c1hub_earning_reverse / user_c1hub_compensation_grant
--   user_c3_adjust_create / user_c3_adjust_approve / user_c3_adjust_reverse
-- 其余高敏（冻结/解冻/KYC撤销/强制登出/会话吊销/重置2FA/重置密码/impersonate终止/禁入名单/设备回收/换机）amplifies=0
-- 不含 role_permission 绑定（后续 BaselineInitializer 按域级规则统一处理）

INSERT INTO nx_admin_permission (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted) VALUES
  -- ===== C1 检索 & 画像 /users/search (2) =====
  ('user_c1_read',                       'C1 检索&画像-页面读',              'API', '/users/search',       'READ',  0, 1, 0),
  ('user_c1_write',                      'C1 检索&画像-导出脱敏名单',        'API', '/users/search',       'WRITE', 0, 1, 0),

  -- ===== C1HUB 360 用户画像 /users/search/[id] (14) =====
  ('user_c1hub_read',                    'C1 用户360画像-页面读',            'API', '/users/search/[id]',  'READ',  0, 1, 0),
  ('user_c1hub_write',                   'C1 用户360画像-常规写',            'API', '/users/search/[id]',  'WRITE', 0, 1, 0),
  ('user_c1hub_account_freeze',          'C1HUB 冻结账户',                   'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_account_unfreeze',        'C1HUB 解冻账户',                   'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_session_revoke_all',      'C1HUB 强制登出(全部会话)',         'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_session_revoke_one',      'C1HUB 吊销单会话',                 'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_password_reset',          'C1HUB 重置密码',                   'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_password_force_change',   'C1HUB 强制改密',                   'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_2fa_reset',               'C1HUB 重置2FA',                    'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_device_replace',          'C1HUB 换机',                       'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_device_recycle',          'C1HUB 回收设备',                   'API', '/users/search/[id]',  'HIGH',  0, 1, 0),
  ('user_c1hub_earning_grant',           'C1HUB 补发收益(⚡资金放大)',       'API', '/users/search/[id]',  'HIGH',  1, 1, 0),
  ('user_c1hub_earning_reverse',         'C1HUB 收益红冲(⚡资金放大)',       'API', '/users/search/[id]',  'HIGH',  1, 1, 0),
  ('user_c1hub_compensation_grant',      'C1HUB 客服补偿发放(⚡资金放大)',   'API', '/users/search/[id]',  'HIGH',  1, 1, 0),

  -- ===== C2 账户操作 /users/actions (8) =====
  ('user_c2_read',                       'C2 账户操作-页面读',               'API', '/users/actions',      'READ',  0, 1, 0),
  ('user_c2_write',                      'C2 账户操作-常规写',               'API', '/users/actions',      'WRITE', 0, 1, 0),
  ('user_c2_account_freeze',             'C2 冻结',                          'API', '/users/actions',      'HIGH',  0, 1, 0),
  ('user_c2_account_unfreeze',           'C2 恢复',                          'API', '/users/actions',      'HIGH',  0, 1, 0),
  ('user_c2_session_revoke_all',         'C2 强制登出',                      'API', '/users/actions',      'HIGH',  0, 1, 0),
  ('user_c2_impersonate_start',          'C2 发起只读模拟登录',              'API', '/users/actions',      'HIGH',  0, 1, 0),
  ('user_c2_impersonate_terminate',      'C2 终止模拟会话',                  'API', '/users/actions',      'HIGH',  0, 1, 0),
  ('user_c2_blocklist_add',              'C2 加入禁入名单',                  'API', '/users/actions',      'HIGH',  0, 1, 0),

  -- ===== C3 余额 & 资产调整 /users/assets (5) =====
  ('user_c3_read',                       'C3 余额&资产调整-页面读',          'API', '/users/assets',       'READ',  0, 1, 0),
  ('user_c3_write',                      'C3 余额&资产调整-常规写',          'API', '/users/assets',       'WRITE', 0, 1, 0),
  ('user_c3_adjust_create',              'C3 资产调整发起(⚡资金放大)',      'API', '/users/assets',       'HIGH',  1, 1, 0),
  ('user_c3_adjust_approve',             'C3 资产调整通过(⚡资金放大)',      'API', '/users/assets',       'HIGH',  1, 1, 0),
  ('user_c3_adjust_reverse',             'C3 资产冲正(⚡资金放大)',          'API', '/users/assets',       'HIGH',  1, 1, 0),

  -- ===== C4 KYC 合规台账 /users/kyc (3) =====
  ('user_c4_read',                       'C4 KYC合规台账-页面读',            'API', '/users/kyc',          'READ',  0, 1, 0),
  ('user_c4_write',                      'C4 KYC合规台账-常规写',            'API', '/users/kyc',          'WRITE', 0, 1, 0),
  ('user_c4_kyc_revoke',                 'C4 撤销实名',                      'API', '/users/kyc',          'HIGH',  0, 1, 0),

  -- ===== C5 安全 & 会话 /users/security (6) =====
  ('user_c5_read',                       'C5 安全&会话-页面读',              'API', '/users/security',     'READ',  0, 1, 0),
  ('user_c5_write',                      'C5 安全&会话-常规写',              'API', '/users/security',     'WRITE', 0, 1, 0),
  ('user_c5_session_revoke_one',         'C5 踢线(单会话)',                  'API', '/users/security',     'HIGH',  0, 1, 0),
  ('user_c5_session_revoke_all',         'C5 全部踢线',                      'API', '/users/security',     'HIGH',  0, 1, 0),
  ('user_c5_2fa_disable',                'C5 关闭2FA',                       'API', '/users/security',     'HIGH',  0, 1, 0),
  ('user_c5_password_reset',             'C5 密码重置',                      'API', '/users/security',     'HIGH',  0, 1, 0),

  -- ===== C6 注册/登录风控 /users/reg-risk (2) =====
  ('user_c6_read',                       'C6 注册/登录风控-页面读',          'API', '/users/reg-risk',     'READ',  0, 1, 0),
  ('user_c6_write',                      'C6 注册/登录风控-常规写',          'API', '/users/reg-risk',     'WRITE', 0, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_path = VALUES(resource_path),
  perm_type = VALUES(perm_type),
  amplifies = VALUES(amplifies),
  status = 1, is_deleted = 0;

-- 统计：READ 7 · WRITE 7 · HIGH 26 = 40 权限点
-- amplifies=1 计 6 个（⚡资金放大）：user_c1hub_earning_grant / user_c1hub_earning_reverse / user_c1hub_compensation_grant
--   user_c3_adjust_create / user_c3_adjust_approve / user_c3_adjust_reverse
