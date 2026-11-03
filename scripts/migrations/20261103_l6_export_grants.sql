-- The classic role seed rebuilds BI grants on startup. Apply the L6-specific grants after it.
SET NAMES utf8mb4;
INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES ('bi_l6_export','用户行为聚合CSV导出(无PII)','API','/analytics/behavior-heatmap','READ',0,1,0)
ON DUPLICATE KEY UPDATE status=1,is_deleted=0;

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code='bi_l6_export'
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH','AUDITOR') AND r.status=1 AND r.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;
