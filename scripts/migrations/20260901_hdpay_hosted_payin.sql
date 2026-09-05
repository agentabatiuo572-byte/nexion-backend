CREATE TABLE IF NOT EXISTS nx_hdpay_payin_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_order_id VARCHAR(64) NOT NULL,
    amount_vnd DECIMAL(20, 2) NOT NULL,
    submission_status VARCHAR(24) NOT NULL,
    payment_url VARCHAR(1024) NULL,
    provider_order_id VARCHAR(64) NULL,
    provider_status INT NULL,
    settlement_status VARCHAR(24) NOT NULL DEFAULT 'UNSETTLED',
    settled_usdt DECIMAL(18, 6) NULL,
    wallet_ledger_biz_no VARCHAR(96) NULL,
    settled_at DATETIME NULL,
    request_hash CHAR(64) NOT NULL,
    last_error_code VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nx_hdpay_payin_merchant_order (merchant_order_id),
    UNIQUE KEY uk_nx_hdpay_payin_provider_order (provider_order_id),
    KEY idx_nx_hdpay_payin_submission_updated (submission_status, updated_at),
    KEY idx_nx_hdpay_payin_settlement_updated (settlement_status, updated_at),
    CONSTRAINT chk_nx_hdpay_payin_amount CHECK (amount_vnd > 0),
    CONSTRAINT chk_nx_hdpay_payin_submission CHECK (
        submission_status IN ('PENDING', 'CREATED', 'SUBMIT_UNKNOWN', 'REJECTED')
    ),
    CONSTRAINT chk_nx_hdpay_payin_settlement CHECK (
        settlement_status IN ('UNSETTLED', 'CREDITED', 'MANUAL_REVIEW')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_hdpay_callback_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payload_hash CHAR(64) NOT NULL,
    merchant_order_id VARCHAR(64) NOT NULL,
    provider_order_id VARCHAR(64) NOT NULL,
    provider_status INT NOT NULL,
    amount_vnd DECIMAL(20, 2) NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    claim_token CHAR(36) NULL,
    claimed_at DATETIME NULL,
    provider_query_status INT NULL,
    result_code VARCHAR(64) NULL,
    processed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nx_hdpay_callback_payload (payload_hash),
    KEY idx_nx_hdpay_callback_merchant_created (merchant_order_id, created_at),
    KEY idx_nx_hdpay_callback_processing_created (processing_status, created_at),
    KEY idx_nx_hdpay_callback_recovery (processing_status, updated_at),
    CONSTRAINT chk_nx_hdpay_callback_amount CHECK (amount_vnd > 0),
    CONSTRAINT chk_nx_hdpay_callback_provider_status CHECK (provider_status IN (1, 3, 4, 5)),
    CONSTRAINT chk_nx_hdpay_callback_processing CHECK (
        processing_status IN (
            'PROCESSING', 'OBSERVED', 'AMOUNT_MISMATCH',
            'CREDITED', 'MANUAL_REVIEW'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_hdpay_settlement_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_no CHAR(64) NOT NULL,
    merchant_order_id VARCHAR(64) NOT NULL,
    provider_order_id VARCHAR(64) NOT NULL,
    callback_payload_hash CHAR(64) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nx_hdpay_review_no (review_no),
    UNIQUE KEY uk_nx_hdpay_review_callback (callback_payload_hash),
    KEY idx_nx_hdpay_review_status_created (status, created_at),
    KEY idx_nx_hdpay_review_merchant_created (merchant_order_id, created_at),
    CONSTRAINT chk_nx_hdpay_review_status CHECK (
        status IN ('OPEN', 'RESOLVED', 'DISMISSED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
