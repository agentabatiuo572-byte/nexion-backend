-- B31/X05 read-only stop check. Run on the target after BE-01, before any B2 retirement.
-- A returned role with b2_perms > 0 blocks retirement, even with zero active accounts.
-- Do not grant D3 permissions to make this report empty. Also require BE-36 and SPM-01.
SELECT r.role_code, r.role_name,
       SUM(CASE WHEN x.kind = 'B2_MENU' THEN 1 ELSE 0 END) AS b2_menu,
       SUM(CASE WHEN x.kind = 'B2_PERM' THEN 1 ELSE 0 END) AS b2_perms,
       SUM(CASE WHEN x.kind = 'D3_MENU' THEN 1 ELSE 0 END) AS d3_menu,
       SUM(CASE WHEN x.kind = 'D3_READ' THEN 1 ELSE 0 END) AS d3_read,
       (SELECT COUNT(DISTINCT rr.admin_id)
          FROM nx_admin_role_relation rr
          JOIN nx_admin a ON a.id = rr.admin_id AND a.status = 1 AND a.is_deleted = 0
         WHERE rr.role_id = r.id AND rr.is_deleted = 0) AS active_accounts
  FROM nx_admin_role r
  JOIN (
    SELECT rm.role_id, CONCAT(m.menu_code, '_MENU') AS kind
      FROM nx_admin_role_menu rm
      JOIN nx_admin_menu m ON m.id = rm.menu_id AND m.status = 1 AND m.is_deleted = 0
     WHERE rm.is_deleted = 0 AND m.menu_code IN ('B2', 'D3')
    UNION ALL
    SELECT rp.role_id, IF(p.permission_code = 'finance_d3_read', 'D3_READ', 'B2_PERM')
      FROM nx_admin_role_permission rp
      JOIN nx_admin_permission p ON p.id = rp.permission_id AND p.status = 1 AND p.is_deleted = 0
     WHERE rp.is_deleted = 0
       AND p.permission_code IN ('overview_b2_read', 'overview_b2_write', 'overview_b2_export', 'finance_d3_read')
  ) x ON x.role_id = r.id
 WHERE r.status = 1 AND r.is_deleted = 0
 GROUP BY r.id, r.role_code, r.role_name
HAVING (b2_menu > 0 OR b2_perms > 0) AND (d3_menu = 0 OR d3_read = 0)
 ORDER BY r.role_code;
