-- Evidence schema only. The B140 maintenance command writes the receipt on a selected test copy.
CREATE TABLE IF NOT EXISTS nx_b140_test_wallet_reset (
  entry_no VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
  customer_id BIGINT NOT NULL UNIQUE,
  wallet_id BIGINT NOT NULL,
  original_operation_id BIGINT NOT NULL UNIQUE,
  original_receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  server_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount DECIMAL(24,6) NOT NULL CHECK (amount > 0),
  recorded_db_utc DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
