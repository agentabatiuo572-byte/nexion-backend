-- Preserve UPPER(status) with its source charset/collation; do not rewrite it as a CI comparison.
-- Separate equality probes let the reader avoid completed task history while retaining lease checks.
-- Apply before deploying the reader. No task or business row is modified.
-- Optional rollback after reverting the reader:
-- ALTER TABLE nx_compute_task DROP INDEX idx_task_assignment_active;
-- ALTER TABLE nx_compute_task DROP COLUMN canonical_assignment_status;
SET @assignment_read_lock=CONCAT('nx:assignment-read:',LEFT(SHA2(DATABASE(),256),30));
SELECT GET_LOCK(@assignment_read_lock,10) INTO @assignment_read_acquired;
SET @assignment_read_sql=IF(@assignment_read_acquired=1,'SELECT 1','FAIL ASSIGNMENT_READ_LOCK_UNAVAILABLE');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;

SET @assignment_read_valid=(
 SELECT COUNT(*)=6 AND SUM(
   (column_name IN ('user_id','user_device_id') AND data_type='bigint')
   OR (column_name='status' AND column_type='varchar(32)' AND character_set_name IS NOT NULL)
   OR (column_name='source_environment' AND data_type='varchar' AND character_maximum_length<=32)
   OR (column_name='is_deleted' AND data_type='tinyint')
   OR (column_name='lease_expires_at' AND data_type='datetime'))=6
 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_compute_task'
 AND column_name IN ('user_id','user_device_id','status','source_environment','is_deleted','lease_expires_at')
) AND EXISTS(SELECT 1 FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='nx_compute_task' AND engine='InnoDB');
SET @assignment_read_sql=IF(@assignment_read_valid=1,'SELECT 1','FAIL ASSIGNMENT_READ_SOURCE_SHAPE_INVALID');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;
SELECT character_set_name,collation_name INTO @assignment_read_charset,@assignment_read_collation
 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_compute_task' AND column_name='status';

-- Validate all existing names before the first DDL, including partial migration recovery.
SET @assignment_read_valid=(SELECT COUNT(*)=0 OR (COUNT(*)=1 AND COALESCE(SUM(
 column_type='varchar(32)' AND extra LIKE '%VIRTUAL GENERATED%' AND is_nullable='YES'
 AND LOWER(REPLACE(REPLACE(generation_expression,CHAR(96),''),' ',''))='upper(status)'
 AND character_set_name=@assignment_read_charset AND collation_name=@assignment_read_collation),0)=1)
 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_compute_task'
 AND column_name='canonical_assignment_status');
SET @assignment_read_sql=IF(@assignment_read_valid=1,'SELECT 1','FAIL ASSIGNMENT_READ_COLUMN_SHAPE_INVALID');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;
SET @assignment_read_index_exists=EXISTS(SELECT 1 FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='nx_compute_task' AND index_name='idx_task_assignment_active');
SET @assignment_read_valid=(SELECT COUNT(*)=6 AND COALESCE(SUM(
 non_unique=1 AND index_type='BTREE' AND sub_part IS NULL AND is_visible='YES' AND collation='A' AND expression IS NULL
 AND ((seq_in_index=1 AND column_name='user_id') OR (seq_in_index=2 AND column_name='user_device_id')
 OR (seq_in_index=3 AND column_name='source_environment') OR (seq_in_index=4 AND column_name='is_deleted')
 OR (seq_in_index=5 AND column_name='canonical_assignment_status') OR (seq_in_index=6 AND column_name='lease_expires_at'))),0)=6
 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_compute_task'
 AND index_name='idx_task_assignment_active');
SET @assignment_read_sql=IF(@assignment_read_index_exists=0 OR @assignment_read_valid=1,
 'SELECT 1','FAIL ASSIGNMENT_READ_INDEX_SHAPE_INVALID');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;

SET @assignment_read_old_lock_wait=@@session.lock_wait_timeout;
SET SESSION lock_wait_timeout=15;
SET @assignment_read_column_exists=EXISTS(SELECT 1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='nx_compute_task' AND column_name='canonical_assignment_status');
SET @assignment_read_sql=IF(@assignment_read_column_exists=1,'SELECT 1',CONCAT(
 'ALTER TABLE nx_compute_task ADD COLUMN canonical_assignment_status VARCHAR(32) CHARACTER SET ',
 @assignment_read_charset,' COLLATE ',@assignment_read_collation,
 ' GENERATED ALWAYS AS (UPPER(status)) VIRTUAL, ALGORITHM=INPLACE, LOCK=NONE'));
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;
SET @assignment_read_sql=IF(@assignment_read_index_exists=1,'SELECT 1',
 'ALTER TABLE nx_compute_task ADD INDEX idx_task_assignment_active (user_id,user_device_id,source_environment,is_deleted,canonical_assignment_status,lease_expires_at), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE assignment_read_stmt FROM @assignment_read_sql;
EXECUTE assignment_read_stmt;
DEALLOCATE PREPARE assignment_read_stmt;
SET SESSION lock_wait_timeout=@assignment_read_old_lock_wait;
SELECT RELEASE_LOCK(@assignment_read_lock);
