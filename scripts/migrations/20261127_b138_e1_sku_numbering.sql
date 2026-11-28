-- Draft IDs and sequences start empty. Existing SKU IDs continue to occupy both catalog keys.
CREATE TABLE IF NOT EXISTS nx_e1_sku_create_request (
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  maker_id BIGINT NOT NULL,
  business_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation_id VARCHAR(64) NULL,
  result_sku_id VARCHAR(64) NULL,
  result_json JSON NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_e1_sku_number_sequence (
  prefix VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  last_number SMALLINT UNSIGNED NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
