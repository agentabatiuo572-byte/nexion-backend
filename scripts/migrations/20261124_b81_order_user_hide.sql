-- B81-BE-01: per-user visibility marker only; order, payment, refund and audit rows stay intact.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_order' AND COLUMN_NAME='user_hidden_at')=0,
  'ALTER TABLE nx_order ADD COLUMN user_hidden_at DATETIME(6) NULL AFTER paid_at',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
