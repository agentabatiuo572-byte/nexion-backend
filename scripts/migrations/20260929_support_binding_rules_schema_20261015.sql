-- New on 20261015. The date prefix orders this prerequisite before immutable S3 history.
-- Structure only: existing rows and defaults are untouched. Never replay S3 on an upgraded database.
CREATE TABLE IF NOT EXISTS nx_support_rules (
 id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 1,
 dormant_days INT NULL, maintenance_days INT NULL, activity_window_days INT NULL,
 inheritance_mode VARCHAR(16) NOT NULL DEFAULT 'UNLIMITED', max_inheritance_depth INT NULL,
 updated_by BIGINT NULL,reason VARCHAR(200) NULL,updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
