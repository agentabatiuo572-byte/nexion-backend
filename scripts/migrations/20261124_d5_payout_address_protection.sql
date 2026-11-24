-- Preserve the existing address protection for future saves. Existing address timestamps stay as stored.
INSERT IGNORE INTO nx_config_item
    (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
    ('withdrawal.address_effective_delay_hours', '24', 'NUMBER', 'wallet', 'ADMIN', 'D5 chain payout address activation hours', 1, 0),
    ('withdrawal.address_change_cooldown_days', '7', 'NUMBER', 'wallet', 'ADMIN', 'D5 chain payout address change interval days', 1, 0);
