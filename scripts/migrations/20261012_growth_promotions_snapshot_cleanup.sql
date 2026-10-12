-- Bounded expiry cleanup must not scan every actor's cached list.
SET @promotion_snapshot_cleanup_sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_promotion_list_snapshot'
      AND INDEX_NAME='idx_promotion_list_snapshot_expiry')=0,
  'ALTER TABLE nx_promotion_list_snapshot ADD INDEX idx_promotion_list_snapshot_expiry(expires_at,snapshot_id)',
  'SELECT 1');
PREPARE promotion_snapshot_cleanup_stmt FROM @promotion_snapshot_cleanup_sql;
EXECUTE promotion_snapshot_cleanup_stmt;
DEALLOCATE PREPARE promotion_snapshot_cleanup_stmt;

SET @promotion_report_cleanup_sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_promotion_report_snapshot'
      AND INDEX_NAME='idx_promotion_report_expiry')=0,
  'ALTER TABLE nx_promotion_report_snapshot ADD INDEX idx_promotion_report_expiry(expires_at,snapshot_id)',
  'SELECT 1');
PREPARE promotion_report_cleanup_stmt FROM @promotion_report_cleanup_sql;
EXECUTE promotion_report_cleanup_stmt;
DEALLOCATE PREPARE promotion_report_cleanup_stmt;
