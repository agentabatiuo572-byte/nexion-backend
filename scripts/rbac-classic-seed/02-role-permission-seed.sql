-- role_permission 绑定（角色×权限，域级规则）
-- 规则：超管=全部；审计=所有 READ 类型；各域主操作角色=该域全部(READ/WRITE/HIGH)；B/L 域全角色。
-- 按 permission_code 前缀（域 slug）+ perm_type 匹配。幂等 INSERT IGNORE。
-- 依赖：00-alter + 01-menu + 各域权限 seed(AB/C/D/EF/GH/IJ/KLM.sql) 已执行。

-- 超管：全部权限
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p WHERE r.role_code='SUPER_ADMIN';

-- 审计：所有 READ 类型（只读，全域）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='AUDITOR' AND p.perm_type='READ' AND p.status=1 AND p.is_deleted=0;

-- A 域(platform_)：config 延伸→仅超管(上面已给)+审计读(上面已给)。运营账号治理高敏不放开。
-- B 域(overview_)：全角色（看板）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'overview_%'
WHERE r.role_code IN ('FINANCE','RISK','CONTENT','GROWTH','SUPPORT','CONFIG_ADMIN') AND p.status=1 AND p.is_deleted=0;
-- C 域(user_)：风控
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'user_%'
WHERE r.role_code='RISK' AND p.status=1 AND p.is_deleted=0;
-- D 域(finance_)：财务+风控
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'finance_%'
WHERE r.role_code IN ('FINANCE','RISK') AND p.status=1 AND p.is_deleted=0;
-- E 域(device_)：增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'device_%'
WHERE r.role_code='GROWTH' AND p.status=1 AND p.is_deleted=0;
-- F 域(network_)：增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'network_%'
WHERE r.role_code='GROWTH' AND p.status=1 AND p.is_deleted=0;
-- G 域(finprod_)：财务+增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'finprod_%'
WHERE r.role_code IN ('FINANCE','GROWTH') AND p.status=1 AND p.is_deleted=0;
-- H 域(growth_)：增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'growth_%'
WHERE r.role_code='GROWTH' AND p.status=1 AND p.is_deleted=0;
-- I 域(content_)：内容
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'content_%'
WHERE r.role_code='CONTENT' AND p.status=1 AND p.is_deleted=0;
-- I3 critical 合规/监管通知同时授予风控执行权；CAP 调整权限不再代替发送权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code='content_i3_critical_send'
WHERE r.role_code='RISK' AND p.status=1 AND p.is_deleted=0;
-- J 域(emergency_)：风控（止血）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'emergency_%'
WHERE r.role_code='RISK' AND p.status=1 AND p.is_deleted=0;
-- K 域(risk_)：风控
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'risk_%'
WHERE r.role_code='RISK' AND p.status=1 AND p.is_deleted=0;
-- L 域(bi_)：全角色（看板，导出类高敏仍按 amplifies 在业务层校验）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'bi_%'
WHERE r.role_code IN ('FINANCE','RISK','CONTENT','GROWTH','SUPPORT','CONFIG_ADMIN') AND p.status=1 AND p.is_deleted=0;
-- M 域(service_)：客服+风控
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'service_%'
WHERE r.role_code IN ('SUPPORT','RISK') AND p.status=1 AND p.is_deleted=0;

-- SUPPORT 补充：C/E/I 域只读（客服查用户画像/设备状态/内容，辅助工单；不授予 write/high）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPPORT'
  AND p.perm_type='READ' AND p.status=1 AND p.is_deleted=0
  AND (p.permission_code LIKE 'user_%' OR p.permission_code LIKE 'device_%' OR p.permission_code LIKE 'content_%');
