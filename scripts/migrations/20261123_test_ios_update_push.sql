-- Existing clients have an unknown native build and remain outside version-targeted sends.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_native_push_installation' AND column_name='app_build')=0,
  'ALTER TABLE nx_native_push_installation ADD COLUMN app_build INT NULL',
  'SELECT 1');
PREPARE native_stmt FROM @ddl;
EXECUTE native_stmt;
DEALLOCATE PREPARE native_stmt;
