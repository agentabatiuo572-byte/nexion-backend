-- Keep the existing amount for all months. Only the stored monthly value is used for new chain orders.
INSERT IGNORE INTO nx_config_item
    (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
WITH RECURSIVE months(month_no) AS (
    SELECT 1 UNION ALL SELECT month_no + 1 FROM months WHERE month_no < 24
)
SELECT CONCAT('growth.phase.month.', month_no, '.complianceReviewThresholdUsdt'),
       '1000', 'NUMBER', 'growth', 'ADMIN', 'H1 monthly chain withdrawal compliance waiting threshold', 1, 0
FROM months;

-- The default preserves the original threshold on existing rows and for the previous writer during rollout.
-- The current writer always supplies the actual H1 value for chain withdrawals and NULL for bank withdrawals.
SET @h1_threshold_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema=DATABASE() AND table_name='nx_withdrawal_order'
             AND column_name='h1_compliance_threshold_usdt'),
    'SELECT 1',
    'ALTER TABLE nx_withdrawal_order ADD COLUMN h1_compliance_threshold_usdt DECIMAL(18,6) NULL DEFAULT 1000'
);
PREPARE h1_threshold_stmt FROM @h1_threshold_ddl;
EXECUTE h1_threshold_stmt;
DEALLOCATE PREPARE h1_threshold_stmt;
