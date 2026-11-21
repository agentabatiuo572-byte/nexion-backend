-- Installation artifacts are registered by the trusted release operator, never by console users.
CREATE TABLE IF NOT EXISTS nx_app_update_artifact (
  artifact_id VARCHAR(96) NOT NULL PRIMARY KEY,
  platform VARCHAR(12) NOT NULL,
  version_name VARCHAR(48) NOT NULL,
  latest_build INT NOT NULL,
  download_url VARCHAR(768) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uk_app_update_platform_build (platform, latest_build)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_app_update_platform (
  platform VARCHAR(12) NOT NULL PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0,
  state_json JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO nx_app_update_platform(platform,state_json) VALUES
  ('android',JSON_OBJECT()),('ios',JSON_OBJECT());

CREATE TABLE IF NOT EXISTS nx_app_update_history (
  platform VARCHAR(12) NOT NULL,
  revision BIGINT NOT NULL,
  request_id CHAR(36) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  actor_id VARCHAR(32) NOT NULL,
  actor VARCHAR(128) NOT NULL,
  action VARCHAR(16) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  before_json JSON NOT NULL,
  after_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(platform,revision),
  UNIQUE KEY uk_app_update_command(platform,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
