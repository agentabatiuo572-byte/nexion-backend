-- No financial defaults. Operators submit all nine ranks through F1/A2.
-- Provision the transaction mutex only; never overwrite existing configuration.
CREATE TABLE IF NOT EXISTS nx_admin_operation_mutex (
 lock_key VARCHAR(64) PRIMARY KEY,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO nx_admin_operation_mutex(lock_key) VALUES ('F1_VRANK_BOOTSTRAP');
-- The checker must see all nine exact definitions, not a truncated summary.
ALTER TABLE nx_audit_operation_ticket MODIFY COLUMN after_value TEXT NOT NULL;
