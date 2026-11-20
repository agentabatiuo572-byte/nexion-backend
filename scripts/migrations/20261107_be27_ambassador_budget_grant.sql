-- Existing installations may have the F4 application tables without the budget grant table.
-- Match the baseline schema. Create an empty table; never backfill historical approvals.
CREATE TABLE IF NOT EXISTS nx_team_ambassador_budget_grant (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  application_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  budget_type VARCHAR(32) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_ambassador_budget_grant (application_id, budget_type),
  KEY idx_ambassador_budget_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
