-- 信任中心资料文件（白皮书、纽约州注册证书、FinCEN MSB 登记记录、IRS EIN 通知）。
-- 每个槽位一行，记录运营上传的当前文件；没有行表示使用随代码发布的默认文件，恢复默认即删除该行。
CREATE TABLE IF NOT EXISTS nx_trust_document (
  slot VARCHAR(32) NOT NULL PRIMARY KEY,
  version_label VARCHAR(32) NULL,
  language VARCHAR(32) NULL,
  reference_no VARCHAR(64) NULL,
  verify_url VARCHAR(512) NULL,
  object_key VARCHAR(256) NOT NULL,
  file_name VARCHAR(160) NOT NULL,
  size_bytes BIGINT NOT NULL,
  page_count INT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
