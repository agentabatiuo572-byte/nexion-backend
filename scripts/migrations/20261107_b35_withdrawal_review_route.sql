-- Preserve the submission decision after lifecycle release and audit archival.
-- Existing orders retain NULL and their legacy read projection; no backfill.
SET @b35_route_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
           AND COLUMN_NAME='d2_submission_risk_route'),
  'SELECT 1',
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_submission_risk_route VARCHAR(32) NULL DEFAULT NULL');
PREPARE b35_route_stmt FROM @b35_route_ddl;
EXECUTE b35_route_stmt;
DEALLOCATE PREPARE b35_route_stmt;
