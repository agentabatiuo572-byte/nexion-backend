-- Retire the M2 ticket-desk navigation and its service_m2_* permissions only.
-- Soft-delete: ticket rows, messages, receipts and audit history are untouched,
-- and M1 seat capacity (/api/admin/content/tickets/load-config, service_m1_*) stays live.
-- Runs after the classic RBAC seed in the startup chain, so a reapplied seed cannot
-- resurrect these rows. Safe to reapply.
START TRANSACTION;
UPDATE nx_admin_role_menu rm JOIN nx_admin_menu m ON m.id=rm.menu_id
SET rm.is_deleted=1, rm.updated_at=NOW()
WHERE rm.is_deleted=0
  AND (m.menu_code IN ('M2','MENU_SERVICE_M2') OR m.route_path='/service/tickets');
UPDATE nx_admin_role_permission rp JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1, rp.updated_at=NOW()
WHERE rp.is_deleted=0
  AND (LEFT(p.permission_code,11)='service_m2_' OR p.resource_path='/service/tickets');
UPDATE nx_admin_permission SET status=0,is_deleted=1,updated_at=NOW()
WHERE (status<>0 OR is_deleted<>1)
  AND (LEFT(permission_code,11)='service_m2_' OR resource_path='/service/tickets');
UPDATE nx_admin_menu SET status=0,is_deleted=1,updated_at=NOW()
WHERE (status<>0 OR is_deleted<>1)
  AND (menu_code IN ('M2','MENU_SERVICE_M2') OR route_path='/service/tickets');
COMMIT;
