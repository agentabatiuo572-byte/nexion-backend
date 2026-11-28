-- Retain the existing chain withdrawal floor when enabling the D5 setting.
INSERT IGNORE INTO nx_config_item
    (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
    ('withdrawal.min_usdt', '20', 'NUMBER', 'wallet', 'ADMIN', 'D5 chain withdrawal minimum in USDT', 1, 0);
