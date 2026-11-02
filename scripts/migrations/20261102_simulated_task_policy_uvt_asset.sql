-- Preserve immutable NEX history; new publications use UVT.
-- Discover the original unnamed asset CHECK without relying on its generated name.
SET @sim_asset_old_check = (SELECT tc.CONSTRAINT_NAME
  FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
  WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_sim_task_type_version'
    AND tc.CONSTRAINT_TYPE='CHECK'
    AND REGEXP_LIKE(cc.CHECK_CLAUSE,'(^|[^A-Za-z0-9_])asset([^A-Za-z0-9_]|$)','i'));
SET @sim_asset_check_sql = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_sim_task_type_version'
    AND CONSTRAINT_NAME='chk_sim_task_type_asset' AND CONSTRAINT_TYPE='CHECK')=0,
  CONCAT('ALTER TABLE nx_sim_task_type_version DROP CHECK `',REPLACE(@sim_asset_old_check,'`','``'),
    '`, ADD CONSTRAINT chk_sim_task_type_asset CHECK (asset IN (''USDT'',''NEX'',''UVT''))'),
  'SELECT 1');
PREPARE sim_asset_check_stmt FROM @sim_asset_check_sql;
EXECUTE sim_asset_check_stmt;
DEALLOCATE PREPARE sim_asset_check_stmt;
