SET NAMES utf8mb4;

-- Real business-day aggregates. Active people are deliberately not additive and are not stored here.
CREATE TABLE IF NOT EXISTS nx_business_summary_daily (
    biz_date DATE NOT NULL,
    metric VARCHAR(32) NOT NULL,
    value DECIMAL(28,6) NOT NULL,
    PRIMARY KEY(biz_date,metric)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_business_summary_state (
    id TINYINT NOT NULL PRIMARY KEY,
    statistics_start DATE NULL,
    as_of DATETIME(6) NULL,
    calendar_signature VARCHAR(2048) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO nx_business_summary_state(id) VALUES(1);

-- B36's growth exception grants aggregate sales only; it does not grant E4 detail, BI or funds access.
INSERT INTO nx_admin_permission
 (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES ('overview_b0_sales_read','今日概览销售总额','API','/api/admin/overview/business-summary','READ',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_type=VALUES(resource_type),
 resource_path=VALUES(resource_path),perm_type=VALUES(perm_type),amplifies=0,status=1,is_deleted=0;
DELETE rp FROM nx_admin_role_permission rp
 JOIN nx_admin_role r ON r.id=rp.role_id
 JOIN nx_admin_permission p ON p.id=rp.permission_id
 WHERE p.permission_code='overview_b0_sales_read' AND r.role_code NOT IN ('SUPER_ADMIN','GROWTH');
INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r
 JOIN nx_admin_permission p ON p.permission_code='overview_b0_sales_read' AND p.status=1 AND p.is_deleted=0
 WHERE r.role_code IN ('SUPER_ADMIN','GROWTH') AND r.status=1 AND r.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;
