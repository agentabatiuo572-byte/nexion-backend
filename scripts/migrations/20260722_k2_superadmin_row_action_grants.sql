-- K2 exact row actions: restore the product-mandated platform-admin boundary.
-- Idempotent for databases where the classic role/permission seeds already exist.
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM nx_admin_role r
JOIN nx_admin_permission p
  ON p.permission_code IN (
    'risk_k2_row_flag',
    'risk_k2_row_freeze',
    'risk_k2_row_blockgift',
    'risk_k2_row_boardflag'
  )
WHERE r.role_code = 'SUPER_ADMIN'
  AND r.status = 1
  AND r.is_deleted = 0
  AND p.status = 1
  AND p.is_deleted = 0;
