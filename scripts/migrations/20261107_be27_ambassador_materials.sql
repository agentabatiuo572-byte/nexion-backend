-- BE-27: append-only material revisions and verification history. Existing approved grants are untouched.
CREATE TABLE IF NOT EXISTS nx_team_ambassador_material_revision (
  application_id BIGINT NOT NULL,
  revision BIGINT NOT NULL,
  bucket VARCHAR(16) NOT NULL,
  budget_usdt DECIMAL(18,6) NOT NULL,
  statement TEXT NOT NULL,
  attachments_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(application_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_team_ambassador_material_file (
  file_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  application_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  kind VARCHAR(32) NOT NULL,
  name VARCHAR(180) NOT NULL,
  content_type VARCHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_ambassador_material_file_application(application_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_team_ambassador_material_review (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  application_id BIGINT NOT NULL,
  revision BIGINT NOT NULL,
  stage VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  reviewer_id BIGINT NOT NULL,
  reviewer_label VARCHAR(128) NOT NULL,
  reviewed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  reason VARCHAR(1000) NOT NULL,
  verified_attendees INT NULL,
  recognized_budget_usdt DECIMAL(18,6) NULL,
  missing_kinds_json JSON NOT NULL,
  KEY idx_ambassador_material_review(application_id,revision,stage,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_admin_permission(permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES ('network_f4_ambassador_material_review','大使业务材料核验','API','/network/leadership-pool','WRITE',0,1,0),
       ('network_f4_ambassador_finance_review','大使费用材料核验','API','/network/leadership-pool','WRITE',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),status=1,is_deleted=0;

-- Business verifier is assigned explicitly via A1/A6; no implicit GROWTH grant.
INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND r.status=1 AND r.is_deleted=0
  AND p.permission_code IN ('network_f4_ambassador_material_review','network_f4_ambassador_finance_review')
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();
INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD' AND r.status=1 AND r.is_deleted=0
  AND p.permission_code IN ('network_f4_read','network_f4_ambassador_finance_review')
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();
INSERT INTO nx_admin_role_menu(role_id,menu_id,is_deleted)
SELECT r.id,m.id,0 FROM nx_admin_role r JOIN nx_admin_menu m
WHERE r.role_code='FINANCE_LEAD' AND r.status=1 AND r.is_deleted=0 AND m.menu_code IN ('F','F4') AND m.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();
