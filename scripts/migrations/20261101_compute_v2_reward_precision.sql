-- Run before enabling Compute V2 / admitting the first six-decimal E2 reward edit.
-- 18,5 had thirteen integer places; 18,6 has twelve. STRICT_ALL_TABLES rejects
-- any oversized historical value instead of truncating it. No reward backfill.
SET @compute_v2_previous_sql_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = CONCAT_WS(',',@@SESSION.sql_mode,'STRICT_ALL_TABLES');
ALTER TABLE nx_admin_device_task
  MODIFY COLUMN min_reward DECIMAL(18,6) NOT NULL DEFAULT 0,
  MODIFY COLUMN max_reward DECIMAL(18,6) NOT NULL DEFAULT 0;
SET SESSION sql_mode = @compute_v2_previous_sql_mode;
