-- J3 篡改防御闭环：事件幂等投影、真实告警配置默认值和最小权限边界。

CREATE TABLE IF NOT EXISTS nx_emergency_tamper_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_no VARCHAR(96) NOT NULL,
  user_id BIGINT NULL,
  user_no VARCHAR(32) NULL,
  path_key VARCHAR(64) NOT NULL,
  path_name VARCHAR(128) NOT NULL,
  description VARCHAR(500) NULL,
  cluster_code VARCHAR(64) NULL,
  k4_accepted TINYINT NOT NULL DEFAULT 0,
  k4_delta INT NOT NULL DEFAULT 0,
  b5_accepted TINYINT NOT NULL DEFAULT 0,
  event_count INT NOT NULL DEFAULT 1,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_tamper_event_path_time (path_key, occurred_at),
  KEY idx_tamper_event_user_time (user_id, user_no, occurred_at),
  KEY idx_tamper_event_cluster (cluster_code, occurred_at),
  UNIQUE KEY uk_emergency_tamper_event_no (event_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @j3_k4_accepted_exists = (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'nx_emergency_tamper_event' AND column_name = 'k4_accepted'
);
SET @j3_k4_accepted_sql = IF(
  @j3_k4_accepted_exists = 0,
  'ALTER TABLE nx_emergency_tamper_event ADD COLUMN k4_accepted TINYINT NOT NULL DEFAULT 0 AFTER cluster_code',
  'SELECT 1'
);
PREPARE j3_k4_accepted_stmt FROM @j3_k4_accepted_sql;
EXECUTE j3_k4_accepted_stmt;
DEALLOCATE PREPARE j3_k4_accepted_stmt;

SET @j3_b5_accepted_exists = (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'nx_emergency_tamper_event' AND column_name = 'b5_accepted'
);
SET @j3_b5_accepted_sql = IF(
  @j3_b5_accepted_exists = 0,
  'ALTER TABLE nx_emergency_tamper_event ADD COLUMN b5_accepted TINYINT NOT NULL DEFAULT 0 AFTER k4_delta',
  'SELECT 1'
);
PREPARE j3_b5_accepted_stmt FROM @j3_b5_accepted_sql;
EXECUTE j3_b5_accepted_stmt;
DEALLOCATE PREPARE j3_b5_accepted_stmt;

-- 旧环境可能允许 event_no 为空或重复；先为历史行生成稳定的本地编号，再加唯一约束。
UPDATE nx_emergency_tamper_event
   SET event_no = CONCAT('legacy-', id)
 WHERE event_no IS NULL OR TRIM(event_no) = '';

UPDATE nx_emergency_tamper_event target
JOIN (
  SELECT event_no, MIN(id) AS keeper_id
    FROM nx_emergency_tamper_event
   GROUP BY event_no
  HAVING COUNT(*) > 1
) duplicate_event ON duplicate_event.event_no = target.event_no
   SET target.event_no = CONCAT(LEFT(target.event_no, 70), '-', target.id)
 WHERE target.id <> duplicate_event.keeper_id;

ALTER TABLE nx_emergency_tamper_event
  MODIFY event_no VARCHAR(96) NOT NULL;

SET @j3_event_unique_exists = (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'nx_emergency_tamper_event'
     AND index_name = 'uk_emergency_tamper_event_no'
);
SET @j3_event_unique_sql = IF(
  @j3_event_unique_exists = 0,
  'ALTER TABLE nx_emergency_tamper_event ADD UNIQUE KEY uk_emergency_tamper_event_no (event_no)',
  'SELECT 1'
);
PREPARE j3_event_unique_stmt FROM @j3_event_unique_sql;
EXECUTE j3_event_unique_stmt;
DEALLOCATE PREPARE j3_event_unique_stmt;

CREATE TABLE IF NOT EXISTS nx_emergency_control_setting (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  setting_key VARCHAR(128) NOT NULL,
  setting_value VARCHAR(512) NOT NULL,
  value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
  group_code VARCHAR(64) NOT NULL,
  remark VARCHAR(500) NULL,
  operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_emergency_control_setting_key (setting_key),
  KEY idx_emergency_control_setting_group (group_code, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_emergency_control_setting
  (setting_key, setting_value, value_type, group_code, remark, operator, is_deleted)
VALUES
  ('emergency.tamper.alert.threshold', '10', 'NUMBER', 'admin_tamper', 'J3 高频告警阈值', 'migration', 0),
  ('emergency.tamper.alert.feedK4', 'true', 'BOOLEAN', 'admin_tamper', 'J3 命中同步 K4 风险信号', 'migration', 0)
ON DUPLICATE KEY UPDATE
  value_type = VALUES(value_type),
  group_code = VALUES(group_code),
  is_deleted = 0;

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('emergency_j3_read', '篡改防御监控-读', 'API', '/emergency/tamper', 'READ', 0, 1, 0),
  ('emergency_j3_export', '篡改防御监控-脱敏导出', 'API', '/emergency/tamper', 'READ', 0, 1, 0),
  ('emergency_j3_alert_config', '篡改告警阈值配置(影响K4风险评分)', 'API', '/emergency/tamper', 'HIGH', 0, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  perm_type = VALUES(perm_type),
  amplifies = VALUES(amplifies),
  status = 1,
  is_deleted = 0;

-- 当前 RBAC 没有角色内的 lead 层级。按最小权限原则，配置权暂只给超级管理员；
-- 待 A1 建立可验证的风控 lead 层级后，再把该权限授予 lead，而不是整个 RISK 角色。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT role_row.id, permission_row.id
  FROM nx_admin_role role_row
  JOIN nx_admin_permission permission_row
    ON permission_row.permission_code IN ('emergency_j3_read', 'emergency_j3_export')
 WHERE role_row.role_code IN ('SUPER_ADMIN', 'RISK', 'AUDITOR')
   AND role_row.status = 1 AND role_row.is_deleted = 0
   AND permission_row.status = 1 AND permission_row.is_deleted = 0;

UPDATE nx_admin_role_permission role_permission
JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
   SET role_permission.is_deleted = 0
 WHERE role_row.role_code IN ('SUPER_ADMIN', 'RISK', 'AUDITOR')
   AND permission_row.permission_code IN ('emergency_j3_read', 'emergency_j3_export');

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT role_row.id, permission_row.id
  FROM nx_admin_role role_row
  JOIN nx_admin_permission permission_row
    ON permission_row.permission_code = 'emergency_j3_alert_config'
 WHERE role_row.role_code = 'SUPER_ADMIN'
   AND role_row.status = 1 AND role_row.is_deleted = 0
   AND permission_row.status = 1 AND permission_row.is_deleted = 0;

UPDATE nx_admin_role_permission role_permission
JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
   SET role_permission.is_deleted = 0
 WHERE role_row.role_code = 'SUPER_ADMIN'
   AND permission_row.permission_code = 'emergency_j3_alert_config';

DELETE role_permission
  FROM nx_admin_role_permission role_permission
  JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
  JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
 WHERE permission_row.permission_code IN ('emergency_j3_read', 'emergency_j3_export')
   AND role_row.role_code NOT IN ('SUPER_ADMIN', 'RISK', 'AUDITOR');

DELETE role_permission
  FROM nx_admin_role_permission role_permission
  JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
  JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
 WHERE permission_row.permission_code = 'emergency_j3_alert_config'
   AND role_row.role_code <> 'SUPER_ADMIN';

DELETE role_permission
  FROM nx_admin_role_permission role_permission
  JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
 WHERE permission_row.permission_code = 'emergency_j3_write';

UPDATE nx_admin_permission
   SET status = 0, is_deleted = 1
 WHERE permission_code = 'emergency_j3_write';
