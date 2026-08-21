-- Versioned server-owned policy consumed by the UniApp ambassador form.
CREATE TABLE IF NOT EXISTS nx_team_ambassador_policy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_key VARCHAR(64) NOT NULL,
  policy_version VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  default_budget_usdt DECIMAL(18,6) NOT NULL,
  buckets_json JSON NOT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_team_ambassador_policy_key (policy_key),
  CONSTRAINT chk_team_ambassador_policy_budget CHECK (default_budget_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_team_ambassador_policy
  (policy_key,policy_version,revision,default_budget_usdt,buckets_json,active,is_deleted)
VALUES
  ('default','ambassador-v1',1,3000.000000,
   '[{"id":"venue","title":"Event venue","range":"$1,000 — $10,000","rule":"Host an in-person event","minBudgetUsdt":1000,"maxBudgetUsdt":10000},{"id":"kol","title":"KOL / Creator","range":"$500 — $5,000","rule":"Creator-led distribution","minBudgetUsdt":500,"maxBudgetUsdt":5000},{"id":"print","title":"Print & OOH","range":"$1,000 — $8,000","rule":"Physical community visibility","minBudgetUsdt":1000,"maxBudgetUsdt":8000},{"id":"dev","title":"Developer workshop","range":"$300 — $3,000","rule":"Hands-on technical session","minBudgetUsdt":300,"maxBudgetUsdt":3000}]',1,0)
ON DUPLICATE KEY UPDATE
  policy_version=VALUES(policy_version),revision=VALUES(revision),default_budget_usdt=VALUES(default_budget_usdt),
  buckets_json=VALUES(buckets_json),active=VALUES(active),is_deleted=VALUES(is_deleted);
