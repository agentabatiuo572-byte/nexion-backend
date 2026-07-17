-- 域 A+B · 平台基础+总览 · 32 权限点
-- 源：docs/superpowers/specs/rbac-classic/AB.md（13 页 · READ 13 · WRITE 7 · HIGH 12）
-- 表：nx_admin_permission（schema 见 00-permission-alter.sql）
-- 幂等：permission_code 唯一键，重复执行只更新不报错。
-- amplifies=1 的 3 个：A2 operation_approve（资金放行·amplifies 工单挂 B1 红线）/ B1 redline_write / B1 runrisk_write
-- 不含 role_permission 绑定（后续 BaselineInitializer 按域级规则统一处理）

INSERT INTO nx_admin_permission (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted) VALUES
  -- ===== A1 运营账号 & RBAC /platform/rbac (8) =====
  ('platform_a1_read',                   'A1 运营账号&RBAC-页面读',           'API', '/platform/rbac',             'READ',  0, 1, 0),
  ('platform_a1_write',                  'A1 运营账号&RBAC-页面常规写',       'API', '/platform/rbac',             'WRITE', 0, 1, 0),
  ('platform_a1_account_role_change',    'A1 改角色',                         'API', '/platform/rbac',             'HIGH',  0, 1, 0),
  ('platform_a1_account_2fa_reset',      'A1 重置2FA',                        'API', '/platform/rbac',             'HIGH',  0, 1, 0),
  ('platform_a1_account_password_reset', 'A1 重置密码',                       'API', '/platform/rbac',             'HIGH',  0, 1, 0),
  ('platform_a1_account_sessions_revoke','A1 强制登出',                       'API', '/platform/rbac',             'HIGH',  0, 1, 0),
  ('platform_a1_account_disable',        'A1 禁用账号',                       'API', '/platform/rbac',             'HIGH',  0, 1, 0),
  ('platform_a1_rbac_grants_update',     'A1 改授权(RBAC)',                   'API', '/platform/rbac',             'HIGH',  0, 1, 0),

  -- ===== A2 审计 & 操作确认 /platform/audit (4) =====
  ('platform_a2_read',                   'A2 审计&操作确认-页面读',           'API', '/platform/audit',            'READ',  0, 1, 0),
  ('platform_a2_write',                  'A2 审计&操作确认-页面常规写',       'API', '/platform/audit',            'WRITE', 0, 1, 0),
  ('platform_a2_proposal_create',        'A2 按业务权限创建待确认操作',       'API', '/platform/audit',            'WRITE', 0, 1, 0),
  ('platform_a2_operation_approve',      'A2 执行/放行(高敏工单)',            'API', '/platform/audit',            'HIGH',  1, 1, 0),

  -- ===== A3 系统配置 /platform/config (2) =====
  ('platform_a3_read',                   'A3 系统配置-页面读',                'API', '/platform/config',           'READ',  0, 1, 0),
  ('platform_a3_write',                  'A3 系统配置-功能开关切换',          'API', '/platform/config',           'WRITE', 0, 1, 0),

  -- ===== A4 埋点事件 /platform/events (2) =====
  ('platform_a4_read',                   'A4 埋点事件-页面读',                'API', '/platform/events',           'READ',  0, 1, 0),
  ('platform_a4_write',                  'A4 埋点事件-页面常规写',            'API', '/platform/events',           'WRITE', 0, 1, 0),

  -- ===== A5 平台参数寄存器 /platform/params-registry (1) =====
  ('platform_a5_read',                   'A5 平台参数寄存器-只读索引',        'API', '/platform/params-registry',  'READ',  0, 1, 0),

  -- ===== A6 角色管理 /platform/roles (3) =====
  ('platform_a6_read',                   'A6 角色管理-页面读',                'API', '/platform/roles',           'READ',  0, 1, 0),
  ('platform_a6_write',                  'A6 角色元数据CRUD',                 'API', '/platform/roles',           'WRITE', 0, 1, 0),
  ('platform_a6_role_grants_update',     'A6 改角色权限/菜单绑定',            'API', '/platform/roles',           'HIGH',  0, 1, 0),

  -- ===== A7 菜单管理 /platform/menus (2) =====
  ('platform_a7_read',                   'A7 菜单管理-页面读',                'API', '/platform/menus',           'READ',  0, 1, 0),
  ('platform_a7_write',                  'A7 菜单节点CRUD',                   'API', '/platform/menus',           'WRITE', 0, 1, 0),

  -- ===== A8 权限字典 /platform/permissions (1) =====
  ('platform_a8_read',                   'A8 权限字典-只读',                  'API', '/platform/permissions',     'READ',  0, 1, 0),

  -- ===== B1 双账本总览 /overview/dual-ledger (5) =====
  ('overview_b1_read',                   'B1 双账本总览-页面读',              'API', '/overview/dual-ledger',      'READ',  0, 1, 0),
  ('overview_b1_write',                  'B1 双账本总览-告警标记处置',        'API', '/overview/dual-ledger',      'WRITE', 0, 1, 0),
  ('overview_b1_redline_write',          'B1 兑付覆盖率红线阈值',             'API', '/overview/dual-ledger',      'HIGH',  1, 1, 0),
  ('overview_b1_runrisk_write',          'B1 挤兑压力红线阈值',               'API', '/overview/dual-ledger',      'HIGH',  1, 1, 0),
  ('overview_b1_kill_switch_trigger',    'B1 触发全局熔断',                   'API', '/overview/dual-ledger',      'HIGH',  0, 1, 0),

  -- ===== B2 资金池水位 /overview/liquidity (1) =====
  ('overview_b2_read',                   'B2 资金池水位-页面读',              'API', '/overview/liquidity',        'READ',  0, 1, 0),

  -- ===== B3 转化漏斗 /overview/funnel (1) =====
  ('overview_b3_read',                   'B3 转化漏斗-页面读',                'API', '/overview/funnel',           'READ',  0, 1, 0),

  -- ===== B4 节奏状态 /overview/rhythm (1) =====
  ('overview_b4_read',                   'B4 节奏状态-页面读',                'API', '/overview/rhythm',           'READ',  0, 1, 0),

  -- ===== B5 风险雷达 /overview/risk-radar (1) =====
  ('overview_b5_read',                   'B5 风险雷达-页面读',                'API', '/overview/risk-radar',       'READ',  0, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_path = VALUES(resource_path),
  perm_type = VALUES(perm_type),
  amplifies = VALUES(amplifies),
  status = 1, is_deleted = 0;

-- 统计：READ 13 · WRITE 8 · HIGH 11 = 32 权限点
-- amplifies=1 计 3 个：platform_a2_operation_approve / overview_b1_redline_write / overview_b1_runrisk_write
