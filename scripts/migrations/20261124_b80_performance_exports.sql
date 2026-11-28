-- B80-BE-06: server-side performance CSV exports (summary / customer detail / transaction detail).
-- Reuses L5 task, approval and download-token practice; the fact and aggregate sources are B80-BE-01/02.
SET NAMES utf8mb4;

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('service_performance_export_summary', '导出客服业绩汇总', 'API', '/content/support-workbench/performance-exports', 'READ', 0, 1, 0),
  ('service_performance_export_detail',  '申请并下载客服业绩明细', 'API', '/content/support-workbench/performance-exports', 'HIGH', 0, 1, 0),
  ('service_performance_export_approve', '审批客服业绩明细导出', 'API', '/content/support-workbench/performance-exports', 'HIGH', 0, 1, 0)
ON DUPLICATE KEY UPDATE permission_name = VALUES(permission_name), updated_at = NOW();

-- Default grant keeps the existing superadmin practice; other roles stay an explicit A1 decision.
INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM nx_admin_role r
JOIN nx_admin_permission p ON p.permission_code IN
  ('service_performance_export_summary','service_performance_export_detail','service_performance_export_approve')
WHERE r.role_code = 'SUPER_ADMIN' AND r.status = 1 AND r.is_deleted = 0
  AND p.resource_type = 'API' AND p.status = 1 AND p.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

CREATE TABLE IF NOT EXISTS nx_support_performance_export_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_key VARCHAR(128) NULL,
  requester_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_support_perf_export_request_key (requester_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_support_performance_export_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_id VARCHAR(40) NOT NULL,
  requester_id BIGINT NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  scope_json JSON NOT NULL,
  window_json JSON NOT NULL,
  preview_version VARCHAR(64) NOT NULL,
  request_reason VARCHAR(200) NOT NULL,
  status VARCHAR(32) NOT NULL,
  version INT NOT NULL DEFAULT 1,
  row_count INT NULL,
  restricted_rows INT NULL,
  columns_json JSON NULL,
  coverage_json JSON NULL,
  content_json MEDIUMTEXT NULL,
  disclosed_customers_json JSON NULL,
  generation_error VARCHAR(200) NULL,
  reviewer_id BIGINT NULL,
  decision_reason VARCHAR(200) NULL,
  approved_at DATETIME NULL,
  file_csv MEDIUMTEXT NULL,
  file_name VARCHAR(128) NULL,
  file_created_at DATETIME NULL,
  delivered_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_support_perf_export_request_type (request_id, task_type),
  KEY idx_support_perf_export_requester (requester_id, created_at),
  KEY idx_support_perf_export_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_support_performance_export_token (
  token_hash CHAR(64) NOT NULL,
  task_id BIGINT NOT NULL,
  requester_id BIGINT NOT NULL,
  expires_at DATETIME NOT NULL,
  used_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (token_hash),
  KEY idx_support_perf_export_token_task (task_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
