-- Forward repair for databases that baselined the legacy engagement migration.
-- Restore only the wheel payout lock; prize pools and cash guards stay unchanged.
INSERT IGNORE INTO nx_admin_operation_mutex (lock_key) VALUES ('H4_WHEEL_PAYOUT');
