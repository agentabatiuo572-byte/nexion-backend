-- Existing E4OrderRefundMapper writes this receipt; canonical order/refund reads
-- also depend on it. Supply the previously missing empty-table prerequisite.
-- Existing installations and financial rows are left unchanged.
CREATE TABLE IF NOT EXISTS nx_wallet_bill (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  bill_no VARCHAR(128) NOT NULL,
  type VARCHAR(64) NOT NULL,
  token VARCHAR(16) NOT NULL,
  amount DECIMAL(18,6) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_wallet_bill_no (bill_no),
  KEY idx_wallet_bill_owner_time (user_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
