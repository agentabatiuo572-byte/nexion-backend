-- Separate version counters for native APKs/store entries and signed WGTs.
CREATE TABLE IF NOT EXISTS nx_app_update_resource (
  artifact_id VARCHAR(96) NOT NULL PRIMARY KEY,
  platform VARCHAR(12) NOT NULL,
  version_name VARCHAR(48) NOT NULL,
  latest_build INT NOT NULL,
  download_url VARCHAR(768) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  app_id VARCHAR(96) NOT NULL,
  min_native_build INT NOT NULL,
  max_native_build INT NOT NULL,
  size_bytes BIGINT NOT NULL,
  signature CHAR(128) NOT NULL,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uk_app_resource_platform_build (platform,latest_build)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
