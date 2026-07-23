-- H7 real voucher ownership grants.
-- The unique grant key and unique source tuple make cross-domain grants replay-safe.
CREATE TABLE IF NOT EXISTS nx_growth_voucher_grant (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    grant_id VARCHAR(96) NOT NULL,
    grant_key VARCHAR(160) NOT NULL,
    voucher_id VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(96) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    used_order_no VARCHAR(96) NULL,
    operator VARCHAR(80) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_nx_growth_voucher_grant_id (grant_id),
    UNIQUE KEY uk_nx_growth_voucher_grant_key (grant_key),
    UNIQUE KEY uk_nx_growth_voucher_grant_source (voucher_id, user_id, source_type, source_id),
    KEY idx_nx_growth_voucher_grant_user (user_id, status),
    KEY idx_nx_growth_voucher_grant_voucher (voucher_id, status)
);
