-- Forward repair for upgraded databases missing the H4 activity mutation lock.
INSERT IGNORE INTO nx_admin_operation_mutex (lock_key) VALUES ('H4_EVENT');
