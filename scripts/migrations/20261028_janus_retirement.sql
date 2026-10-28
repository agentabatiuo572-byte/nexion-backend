-- Retire executable configuration and navigation only. Keep device, command,
-- financial evidence, enrollment and audit history intact. Safe to reapply.
START TRANSACTION;
UPDATE nx_admin_role_menu rm JOIN nx_admin_menu m ON m.id=rm.menu_id
SET rm.is_deleted=1, rm.updated_at=NOW()
WHERE m.menu_code IN ('K6','MENU_RISK_K6') OR m.route_path='/risk/janus-c2';
UPDATE nx_admin_role_permission rp JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1, rp.updated_at=NOW()
WHERE LEFT(p.permission_code,8)='risk_k6_' OR p.resource_path='/api/admin/janus' OR p.resource_path LIKE '/api/admin/janus/%';
UPDATE nx_admin_permission SET status=0,is_deleted=1,updated_at=NOW()
WHERE LEFT(permission_code,8)='risk_k6_' OR resource_path='/api/admin/janus' OR resource_path LIKE '/api/admin/janus/%';
UPDATE nx_admin_menu SET status=0,is_deleted=1,updated_at=NOW()
WHERE menu_code IN ('K6','MENU_RISK_K6') OR route_path='/risk/janus-c2';
UPDATE nx_config_item SET status=0,updated_at=NOW()
WHERE config_key IN ('E.compute.computeShareEnabled','risk.k1.release.appAttestationReleaseHours','platform.compute.download.allowedTargets')
   OR config_key LIKE 'E.compute.gpuTier.%' OR config_key LIKE 'E.compute.download.%';
UPDATE nx_config_item SET config_value='manual_only',updated_at=NOW()
WHERE config_key='risk.k1.release.releaseMode' AND config_value<>'manual_only';
COMMIT;
