CREATE TABLE IF NOT EXISTS nx_user_sku_entitlement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  fulfillment_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  sku_id VARCHAR(64) NOT NULL,
  rank_code VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'GRANTED',
  source VARCHAR(32) NOT NULL DEFAULT 'VRANK_REWARD',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_sku_entitlement_fulfillment (fulfillment_id),
  KEY idx_user_sku_entitlement_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
