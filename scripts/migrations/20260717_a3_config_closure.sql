-- A3 closure: one persisted flag with a real runtime consumer, authoritative read roles and least privilege.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('feature.ops.maintenanceBanner', 'off', 'STRING', 'admin_feature_flag', 'ADMIN',
   'A3 registered platform flag; consumer=ops-console-shell; allowed=on,off', 1, 0)
ON DUPLICATE KEY UPDATE
  value_type='STRING', config_group='admin_feature_flag', visibility='ADMIN',
  remark='A3 registered platform flag; consumer=ops-console-shell; allowed=on,off', status=1, is_deleted=0;

-- These rows came from the former A3 demonstration catalog and have no registered runtime consumer.
UPDATE nx_config_item
   SET status=0, remark='Retired from A3: no registered runtime consumer', updated_at=CURRENT_TIMESTAMP
 WHERE config_key IN (
       'feature.ab.newWithdrawFlow',
       'feature.ab.homeBannerExp',
       'feature.exp.questBoostAB',
       'feature.core.sse_v2')
   AND config_group='admin_feature_flag'
   AND is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code='platform_a3_read'
 WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','RISK','GROWTH','AUDITOR')
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code='platform_a3_write'
 WHERE r.role_code='SUPER_ADMIN'
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

DELETE rp
  FROM nx_admin_role_permission rp
  JOIN nx_admin_role r ON r.id=rp.role_id
  JOIN nx_admin_permission p ON p.id=rp.permission_id
 WHERE p.permission_code='platform_a3_write'
   AND r.role_code<>'SUPER_ADMIN';

INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id
  FROM nx_admin_role r
  JOIN nx_admin_menu m ON m.menu_code IN ('A','A3')
 WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','RISK','GROWTH','AUDITOR')
   AND r.status=1 AND r.is_deleted=0 AND m.status=1 AND m.is_deleted=0;

DELETE rm
  FROM nx_admin_role_menu rm
  JOIN nx_admin_role r ON r.id=rm.role_id
  JOIN nx_admin_menu m ON m.id=rm.menu_id
 WHERE m.menu_code='A3'
   AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE','RISK','GROWTH','AUDITOR');
