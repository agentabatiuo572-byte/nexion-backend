-- G4 configuration uses this shared InnoDB row until transaction completion.
-- The baseline seeds it, but upgraded databases also need a forward migration.
-- Only provision the mutex; preserve all operator-owned configuration and data.
CREATE TABLE IF NOT EXISTS nx_admin_operation_mutex (
    lock_key VARCHAR(64) PRIMARY KEY,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO nx_admin_operation_mutex (lock_key) VALUES ('G4_CONFIG');
