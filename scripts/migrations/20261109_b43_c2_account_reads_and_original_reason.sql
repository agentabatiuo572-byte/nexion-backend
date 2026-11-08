-- C2 original user reason is create-only. Historical reason may already be a
-- reviewer/cancellation reason, so legacy rows deliberately remain NULL.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_account_deletion_request'
      AND COLUMN_NAME='original_reason')=0,
  'ALTER TABLE nx_user_account_deletion_request ADD COLUMN original_reason VARCHAR(255) NULL AFTER reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Supports stable, complete state-filtered C2 pagination without C1 joins.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user'
      AND INDEX_NAME='idx_user_c2_status_frozen')=0,
  'ALTER TABLE nx_user ADD INDEX idx_user_c2_status_frozen (status,is_deleted,c2_frozen_at,id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
