CREATE TABLE IF NOT EXISTS nx_trial_purchase_options (
    id TINYINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    product_nos JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO nx_trial_purchase_options (id, version, product_nos) VALUES (1, 0, JSON_ARRAY());

CREATE TABLE IF NOT EXISTS nx_trial_purchase_options_command (
    command_key VARBINARY(128) NOT NULL PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    request_json JSON NOT NULL,
    receipt_json JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_trial_balance_submission (
    user_id BIGINT NOT NULL,
    claim_no VARCHAR(96) NOT NULL,
    command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json JSON NOT NULL,
    PRIMARY KEY (user_id, command_key),
    KEY ix_trial_balance_claim (user_id, claim_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_trial_external_rejection (
    user_id BIGINT NOT NULL,
    command_key VARCHAR(128) NOT NULL,
    quote_id VARCHAR(64) NOT NULL,
    receipt_json JSON NOT NULL,
    PRIMARY KEY (user_id, command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE nx_admin_permission
   SET permission_name = '免费试用设置修改'
 WHERE permission_code = 'growth_h2_write' AND is_deleted = 0;
