-- C5 live repair: PRD requires RISK and SUPPORT to revoke one or all user sessions.
-- Keep the grant least-privileged: do not grant user_c5_write or other high-risk actions.
INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('user_c5_session_revoke_one','C5 踢线(单会话)','API','/users/security','HIGH',0,1,0),
  ('user_c5_session_revoke_all','C5 全部踢线','API','/users/security','HIGH',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type),amplifies=VALUES(amplifies),status=1,is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','RISK','SUPPORT')
   AND p.permission_code IN ('user_c5_session_revoke_one','user_c5_session_revoke_all')
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;
