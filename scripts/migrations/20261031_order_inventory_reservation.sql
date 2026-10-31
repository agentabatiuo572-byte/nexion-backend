-- Compatibility first: existing writers/rows already consumed merchandise inventory.
-- No data release, financial update, order cancellation or repricing is performed here.
SET @inventory_reserved_exists := (
 SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_order' AND COLUMN_NAME='inventory_reserved'
);
SET @inventory_reserved_sql := IF(@inventory_reserved_exists=0,
 'ALTER TABLE nx_order ADD COLUMN inventory_reserved TINYINT NOT NULL DEFAULT 1 COMMENT ''1=stock already consumed, 0=consume atomically on payment'' AFTER activation_status',
 'SELECT 1');
PREPARE inventory_reserved_stmt FROM @inventory_reserved_sql;
EXECUTE inventory_reserved_stmt;
DEALLOCATE PREPARE inventory_reserved_stmt;
