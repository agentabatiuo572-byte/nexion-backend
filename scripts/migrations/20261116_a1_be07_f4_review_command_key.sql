-- A1-BE-07: retain the original command key on new F4 material-review history rows.
-- Registered after the F4 material tables in the startup migration list.
-- The managed release migration scanner also discovers this forward migration.
-- Existing review rows are not backfilled.
SET @f4_review_command_key_exists = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'nx_team_ambassador_material_review'
       AND column_name = 'idempotency_key'
);
SET @f4_review_command_key_sql = IF(@f4_review_command_key_exists = 0,
    'ALTER TABLE nx_team_ambassador_material_review ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER missing_kinds_json',
    'SELECT 1');
PREPARE f4_review_command_key_stmt FROM @f4_review_command_key_sql;
EXECUTE f4_review_command_key_stmt;
DEALLOCATE PREPARE f4_review_command_key_stmt;
