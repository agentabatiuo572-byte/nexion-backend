-- Create missing funds-preparation tables before the ERC20 migration.
-- Provider configuration is separate. Existing tables and rows are kept for the forward migration.
CREATE TABLE IF NOT EXISTS nx_cregis_funds_preparation_config (
  project_id BIGINT NOT NULL PRIMARY KEY,
  platform_address VARCHAR(42) NOT NULL,
  counterparty_address VARCHAR(42) NOT NULL,
  network VARCHAR(20) NOT NULL,
  asset VARCHAR(10) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_funds_preparation_plan (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  plan_no VARCHAR(48) NOT NULL,
  project_id BIGINT NOT NULL,
  kind VARCHAR(24) NOT NULL,
  network VARCHAR(10) NOT NULL DEFAULT 'BEP20',
  source_address VARCHAR(42) NOT NULL,
  target_address VARCHAR(42) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  business_reference VARCHAR(100) NOT NULL,
  reason VARCHAR(300) NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  maker_id BIGINT NOT NULL,
  checker_id BIGINT NULL,
  decision_reason VARCHAR(300) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cregis_preparation_no(plan_no),
  UNIQUE KEY uk_cregis_preparation_reference(project_id,kind,business_reference),
  KEY ix_cregis_preparation_page(project_id,network,id),
  CONSTRAINT ck_cregis_preparation_state CHECK (state IN ('DRAFT','REVIEWED','CANCELLED')),
  CONSTRAINT ck_cregis_preparation_kind CHECK (kind IN ('COLLECTION','SUPPLIER_PAYMENT')),
  CONSTRAINT ck_cregis_preparation_network CHECK (network IN ('BEP20','ERC20')),
  CONSTRAINT ck_cregis_preparation_amount CHECK (amount_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
