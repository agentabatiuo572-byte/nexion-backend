-- B80-BE-06: both support employments share SUPPORT. Live employment and scope
-- checks restrict detail export to supervisors. The approval grant is unchanged.
INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code IN
       ('service_performance_export_summary','service_performance_export_detail')
 WHERE r.role_code='SUPPORT' AND r.status=1 AND r.is_deleted=0
   AND p.resource_type='API' AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE role_id=role_id;

-- The public rule counts visible characters, while audit retains the original spacing.
ALTER TABLE nx_support_performance_export_task
  MODIFY COLUMN request_reason TEXT NOT NULL,
  MODIFY COLUMN decision_reason TEXT NULL;
