CREATE TABLE IF NOT EXISTS nx_i18n_message_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_key VARCHAR(128) NOT NULL,
  version_no INT NOT NULL,
  zh_value VARCHAR(1024) NOT NULL,
  en_value VARCHAR(1024) NOT NULL,
  vi_value VARCHAR(1024) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_i18n_message_version (message_key, version_no),
  KEY idx_i18n_message_version_status (message_key, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_i18n_message_version
  (message_key, version_no, zh_value, en_value, vi_value, status, created_at, updated_at, is_deleted)
SELECT m.message_key,
       1,
       COALESCE(MAX(CASE WHEN m.locale = 'zh-CN' THEN m.message_value END), ''),
       COALESCE(MAX(CASE WHEN m.locale = 'en-US' THEN m.message_value END), ''),
       COALESCE(MAX(CASE WHEN m.locale = 'vi-VN' THEN m.message_value END), ''),
       CASE WHEN COUNT(DISTINCT CASE WHEN m.locale IN ('zh-CN','en-US','vi-VN') AND m.message_value <> '' THEN m.locale END) = 3
            THEN 'PUBLISHED' ELSE 'DRAFT' END,
       MIN(m.created_at),
       MAX(m.updated_at),
       0
  FROM nx_i18n_message m
 WHERE m.is_deleted = 0
   AND NOT EXISTS (
       SELECT 1 FROM nx_i18n_message_version v
        WHERE v.message_key = m.message_key AND v.is_deleted = 0)
 GROUP BY m.message_key;
