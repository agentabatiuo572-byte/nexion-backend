CREATE TABLE IF NOT EXISTS nx_conversation_timeout_policy (
  policy_key VARCHAR(64) PRIMARY KEY,
  warn_minutes INT NOT NULL,
  close_minutes INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(64) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_conversation_timeout_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_no VARCHAR(40) NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  activity_at DATETIME NOT NULL,
  policy_version BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_conversation_timeout_event (conversation_no,event_type,activity_at),
  KEY idx_conversation_timeout_event_time (event_type,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO nx_conversation_timeout_policy
  (policy_key,warn_minutes,close_minutes,version,updated_by,reason,created_at,updated_at)
VALUES ('GLOBAL',1,5,1,'system','系统默认会话闲置策略',NOW(),NOW());
