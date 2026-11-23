-- NULL means unlimited. Preserve every existing numeric cap, including zero.
SET @lc3_support_cap_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_support_agent_profile'
              AND COLUMN_NAME = 'max_concurrent' AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT IS NULL),
    'DO 0',
    'ALTER TABLE nx_support_agent_profile MODIFY COLUMN max_concurrent INT NULL DEFAULT NULL'
);
PREPARE lc3_support_cap_stmt FROM @lc3_support_cap_ddl;
EXECUTE lc3_support_cap_stmt;
DEALLOCATE PREPARE lc3_support_cap_stmt;
