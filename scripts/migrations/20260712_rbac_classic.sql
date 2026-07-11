-- 经典 RBAC 正式迁移入口。请从仓库根目录用 MySQL client 执行：
-- mysql --default-character-set=utf8mb4 -uroot -p nexion < scripts/migrations/20260712_rbac_classic.sql
-- 顺序是闭环约束：表结构 -> 角色 -> 菜单 -> 权限 -> 绑定 -> 校验。

SOURCE scripts/rbac-classic-seed/00-permission-alter.sql;

INSERT INTO nx_admin_role (role_code, role_name, remark, status, is_deleted) VALUES
('SUPER_ADMIN', '超级管理员', '平台全域管理员', 1, 0),
('CONFIG_ADMIN', '配置运营', '平台配置与系统参数管理员', 1, 0),
('FINANCE', '财务', '资金、账务与提现审核', 1, 0),
('RISK', '风控', '风控、KYC 与紧急处置', 1, 0),
('CONTENT', '内容运营', '内容、公告与披露管理', 1, 0),
('GROWTH', '增长运营', '增长、设备与网络运营', 1, 0),
('SUPPORT', '客服', '客服中心全局后台角色', 1, 0),
('AUDITOR', '只读审计', '审计与合规只读观察', 1, 0)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), remark=VALUES(remark),
 status=1, is_deleted=0, updated_at=NOW();

SOURCE scripts/rbac-classic-seed/01-menu-seed.sql;
SOURCE scripts/rbac-classic-seed/AB.sql;
SOURCE scripts/rbac-classic-seed/C.sql;
SOURCE scripts/rbac-classic-seed/D.sql;
SOURCE scripts/rbac-classic-seed/EF.sql;
SOURCE scripts/rbac-classic-seed/GH.sql;
SOURCE scripts/rbac-classic-seed/IJ.sql;
SOURCE scripts/rbac-classic-seed/KLM.sql;
SOURCE scripts/rbac-classic-seed/02-role-permission-seed.sql;

SELECT IF(COUNT(*) >= 273, 'RBAC_CLASSIC_OK', 'RBAC_CLASSIC_INCOMPLETE') AS migration_status
  FROM nx_admin_permission
 WHERE status=1 AND is_deleted=0 AND resource_type='API' AND permission_code NOT LIKE 'PERM\\_%';
