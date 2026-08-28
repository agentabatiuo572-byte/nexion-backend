-- H2 / Earn free-trial hero-card quota. The PC-owned policy is the daily
-- ceiling; the dated business row keeps today's atomic claim count.
CREATE TABLE IF NOT EXISTS nx_growth_trial_daily_quota (
  quota_date DATE NOT NULL,
  daily_limit INT UNSIGNED NOT NULL,
  claimed_count INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (quota_date),
  CONSTRAINT chk_growth_trial_daily_quota_limit CHECK (daily_limit <= 1000000),
  CONSTRAINT chk_growth_trial_daily_quota_claimed CHECK (claimed_count <= 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_growth_trial_policy
  (policy_key,policy_name,description,current_value,value_type,hot,section,server_only,sort_order,is_deleted)
VALUES
  ('seatsLeftToday','每日免费名额上限','5173 赚取页顶部免费试用卡的每日名额上限；今日剩余由服务端按日期和已领取数计算','47','NUMBER',0,'live',0,200,0)
ON DUPLICATE KEY UPDATE
  policy_key=VALUES(policy_key);

INSERT INTO nx_growth_trial_daily_quota(quota_date,daily_limit,claimed_count)
SELECT CURRENT_DATE,CAST(current_value AS UNSIGNED),0
  FROM nx_growth_trial_policy
 WHERE policy_key='seatsLeftToday' AND is_deleted=0
   AND current_value REGEXP '^[0-9]+$'
   AND CAST(current_value AS UNSIGNED)<=1000000
ON DUPLICATE KEY UPDATE daily_limit=VALUES(daily_limit);
