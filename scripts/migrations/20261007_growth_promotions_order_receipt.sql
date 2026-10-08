-- Durable quote-to-order receipt, including quotes with zero eligible rewards.
-- No policies, activities, accounts or permissions are seeded.
CREATE TABLE IF NOT EXISTS nx_promotion_order_receipt (
  order_no VARCHAR(96) NOT NULL PRIMARY KEY,
  order_id BIGINT NOT NULL,
  quote_id VARCHAR(64) NOT NULL,
  buyer_id BIGINT NOT NULL,
  projection_json JSON NOT NULL,
  pay_by DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_promotion_receipt_order_id (order_id),
  UNIQUE KEY uk_promotion_receipt_quote (quote_id),
  CONSTRAINT fk_promotion_receipt_order FOREIGN KEY (order_id) REFERENCES nx_order(id),
  CONSTRAINT fk_promotion_receipt_quote FOREIGN KEY (quote_id) REFERENCES nx_promotion_quote(quote_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
