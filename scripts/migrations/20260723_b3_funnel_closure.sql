-- B3 canonical A4 funnel, saved-view persistence and exact RBAC.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_funnel_view (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT NOT NULL,
  view_name VARCHAR(80) NOT NULL,
  cohort VARCHAR(16) NOT NULL DEFAULT 'ALL',
  phase VARCHAR(8) NOT NULL DEFAULT 'ALL',
  ref_code VARCHAR(64) NOT NULL DEFAULT 'ALL',
  granularity VARCHAR(16) NOT NULL DEFAULT 'WEEK',
  comparison VARCHAR(24) NOT NULL DEFAULT 'PREVIOUS',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_funnel_view(admin_id,view_name),
  KEY idx_admin_funnel_view_updated(admin_id,is_deleted,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('overview_b3_read','B3 转化漏斗-页面读','API','/overview/funnel','READ',0,1,0),
  ('overview_b3_view_write','B3 转化漏斗-保存个人视图','API','/overview/funnel','WRITE',0,1,0),
  ('overview_b3_export','B3 转化漏斗-聚合导出','API','/overview/funnel','WRITE',0,1,0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),
  resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type),
  status=1,
  is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code IN ('overview_b3_view_write','overview_b3_export')
  AND r.role_code NOT IN ('SUPER_ADMIN','GROWTH','FINANCE','FINANCE_LEAD','AUDITOR');

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH','FINANCE','FINANCE_LEAD')
  AND p.permission_code='overview_b3_view_write'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH','FINANCE','FINANCE_LEAD','AUDITOR')
  AND p.permission_code='overview_b3_export'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;
