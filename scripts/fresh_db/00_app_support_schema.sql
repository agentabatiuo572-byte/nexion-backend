-- Fresh-database prerequisite for the S3 support migrations.
-- Same DDL that MybatisSupportAgentRepository.ensureSchema() runs at app startup
-- (SupportAgentMapper: createProfileTable / createAssignmentTable / addActiveUser*),
-- applied ahead of time because the startup migration chain assumes these tables exist.
CREATE TABLE IF NOT EXISTS nx_support_agent_profile (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  admin_id BIGINT NOT NULL,
  seat_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
  position VARCHAR(64) NOT NULL,
  service_types VARCHAR(255) NOT NULL,
  tags VARCHAR(255) NOT NULL,
  max_concurrent INT NOT NULL DEFAULT 10,
  enabled TINYINT NOT NULL DEFAULT 1,
  transferable TINYINT NOT NULL DEFAULT 1,
  busy TINYINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_support_agent_profile_admin (admin_id),
  KEY idx_support_agent_profile_enabled (enabled, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_support_agent_user_assignment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  agent_admin_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME DEFAULT NULL,
  operator VARCHAR(64) DEFAULT NULL,
  reason VARCHAR(255) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  active_user_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN status = 'ACTIVE' AND is_deleted = 0 THEN user_id ELSE NULL END
  ) STORED,
  KEY idx_support_assignment_agent (agent_admin_id, status, is_deleted),
  KEY idx_support_assignment_user (user_id, status, is_deleted),
  UNIQUE KEY uq_support_assignment_active_user (active_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
