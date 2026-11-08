-- Preserve subsecond issuance times so a full minimum read is not rejected by DATETIME(0) rounding.
-- Existing second-resolution values and all token/acknowledgment rules remain unchanged.
SET @i5_token_precision_sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_disclosure_read_token'
      AND COLUMN_NAME IN ('created_at','expires_at','consumed_at')
      AND DATA_TYPE='datetime' AND DATETIME_PRECISION=6) = 3,
  'SELECT 1',
  'ALTER TABLE nx_disclosure_read_token
     MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
     MODIFY COLUMN expires_at DATETIME(6) NOT NULL,
     MODIFY COLUMN consumed_at DATETIME(6) NULL DEFAULT NULL');
PREPARE i5_token_precision_stmt FROM @i5_token_precision_sql;
EXECUTE i5_token_precision_stmt;
DEALLOCATE PREPARE i5_token_precision_stmt;
