-- Forward-only ADMIN policy. USER/IMPERSONATION config and old migration bytes are untouched.
CREATE TABLE IF NOT EXISTS nx_admin_security_baseline (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  baseline_key VARCHAR(64) NOT NULL,
  label VARCHAR(128) NOT NULL,
  description VARCHAR(512) NOT NULL DEFAULT '',
  baseline_value VARCHAR(128) NOT NULL,
  locked TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 9999,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_security_baseline_key (baseline_key),
  KEY idx_admin_security_baseline_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO nx_admin_security_baseline
  (baseline_key,label,description,baseline_value,locked,sort_order,status,is_deleted)
VALUES ('session','会话基线','后台无有效操作60分钟自动登出；有效操作续期，无绝对登录时限。','60min / unlimited',1,10,1,0)
ON DUPLICATE KEY UPDATE
  description=VALUES(description), baseline_value=VALUES(baseline_value), locked=1,
  updated_at=NOW();
