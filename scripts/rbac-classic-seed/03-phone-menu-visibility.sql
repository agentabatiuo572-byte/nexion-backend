-- PHONE tasks and phone devices moved from E2/E5 to E6. Preserve menu reachability only.
-- Existing endpoint/action authorities remain unchanged; revive a previously deleted menu binding.
INSERT INTO nx_admin_role_menu (role_id, menu_id, is_deleted)
SELECT r.id, target.id, 0
FROM nx_admin_role r
JOIN nx_admin_menu target ON target.menu_code IN ('E', 'E6') AND target.status=1 AND target.is_deleted=0
WHERE r.status=1 AND r.is_deleted=0
  AND (EXISTS (
    SELECT 1 FROM nx_admin_role_menu rm
    JOIN nx_admin_menu source ON source.id=rm.menu_id AND source.status=1 AND source.is_deleted=0
    WHERE rm.role_id=r.id AND rm.is_deleted=0 AND source.menu_code IN ('E2', 'E5')
  ) OR EXISTS (
    SELECT 1 FROM nx_admin_role_permission rp
    JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0 AND p.resource_type='API'
    WHERE rp.role_id=r.id AND rp.is_deleted=0 AND LEFT(p.permission_code,10) IN ('device_e2_', 'device_e5_')
  ))
ON DUPLICATE KEY UPDATE is_deleted=0;
