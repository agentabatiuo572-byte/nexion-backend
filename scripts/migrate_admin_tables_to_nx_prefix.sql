-- One-time migration for legacy admin/RBAC tables without the nx_ prefix.
-- Safe to re-run when the old table has already been renamed.

SET @stmt = (
  SELECT IF(
    EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'admin'
    ) AND NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'nx_admin'
    ),
    'RENAME TABLE `admin` TO `nx_admin`',
    'SELECT ''skip admin'' AS migration_step'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt = (
  SELECT IF(
    EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'admin_role'
    ) AND NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'nx_admin_role'
    ),
    'RENAME TABLE `admin_role` TO `nx_admin_role`',
    'SELECT ''skip admin_role'' AS migration_step'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt = (
  SELECT IF(
    EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'admin_permission'
    ) AND NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'nx_admin_permission'
    ),
    'RENAME TABLE `admin_permission` TO `nx_admin_permission`',
    'SELECT ''skip admin_permission'' AS migration_step'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt = (
  SELECT IF(
    EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'admin_menu'
    ) AND NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'nx_admin_menu'
    ),
    'RENAME TABLE `admin_menu` TO `nx_admin_menu`',
    'SELECT ''skip admin_menu'' AS migration_step'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt = (
  SELECT IF(
    EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'admin_role_relation'
    ) AND NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'nx_admin_role_relation'
    ),
    'RENAME TABLE `admin_role_relation` TO `nx_admin_role_relation`',
    'SELECT ''skip admin_role_relation'' AS migration_step'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt = (
  SELECT IF(
    EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'admin_role_permission'
    ) AND NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'nx_admin_role_permission'
    ),
    'RENAME TABLE `admin_role_permission` TO `nx_admin_role_permission`',
    'SELECT ''skip admin_role_permission'' AS migration_step'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt = (
  SELECT IF(
    EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'admin_role_menu'
    ) AND NOT EXISTS (
      SELECT 1 FROM information_schema.tables
       WHERE table_schema = DATABASE() AND table_name = 'nx_admin_role_menu'
    ),
    'RENAME TABLE `admin_role_menu` TO `nx_admin_role_menu`',
    'SELECT ''skip admin_role_menu'' AS migration_step'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_admin_idempotency_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  scope VARCHAR(96) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
  response_json JSON NULL,
  error_message VARCHAR(512) NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_idem_scope_key (scope, idempotency_key),
  KEY idx_admin_idem_expires (expires_at),
  KEY idx_admin_idem_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
