-- K2 runtime detection closure.
-- 1) Persist five-minute server-canonical leaderboard snapshots.
-- 2) Normalize historical human-readable dispositions to stable cross-domain codes.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_risk_k2_leaderboard_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  period_type VARCHAR(16) NOT NULL,
  period_key VARCHAR(32) NOT NULL,
  snapshot_bucket DATETIME NOT NULL,
  user_id BIGINT NOT NULL,
  cumulative_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  direct_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_k2_leaderboard_snapshot (period_type, period_key, snapshot_bucket, user_id),
  KEY idx_k2_leaderboard_user_time (user_id, snapshot_bucket),
  KEY idx_k2_leaderboard_period_time (period_type, period_key, snapshot_bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE nx_admin_risk_arbitrage_row
   SET disposition = CASE disposition
       WHEN '已标记套利' THEN 'account_flagged'
       WHEN '新人礼已拦截' THEN 'gift_blocked'
       WHEN '已标记刷榜' THEN 'leaderboard_flagged'
       WHEN '已联动 K1 冻结' THEN 'cluster_frozen'
       ELSE disposition
   END,
       updated_at = NOW()
 WHERE disposition IN ('已标记套利','新人礼已拦截','已标记刷榜','已联动 K1 冻结');
