-- CHECK patterns preserve quoted literals; trigger bodies compare byte-for-byte and fail closed on format drift.
-- Immutable formal receipts and all wallet revisions, MySQL 8.0.29+.
-- Writers insert the source-success receipt and ALL affected journal revisions in the wallet transaction.
-- No-cash releases/terminal phases still require a receipt; they may have no journal legs.
-- ordered_events is the exact ordered compound operation; business/source/proof validation is in the writer.
-- These tables never authorize spending or declare READY. Missing source, birth, leg, or continuity is UNKNOWN.
-- No historical backfill and no journal/wallet FK: records survive wallet deletion/rebuild.
-- Application DB grants must be SELECT/INSERT only on these two tables; do not infer an account from CURRENT_USER().
-- UPDATE/DELETE triggers also forbid mutation when an existing broad grant has not yet been narrowed.
-- DDL is not transactional. Run before API startup with writers stopped, mysql WITHOUT --force.
SET @fund_operation_lock=CONCAT('nx:fund-operations:',LEFT(SHA2(DATABASE(),256),36));
SELECT GET_LOCK(@fund_operation_lock,10) INTO @fund_operation_locked;
SET @fund_operation_sql=IF(@fund_operation_locked=1,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_LOCK');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;

CREATE TABLE IF NOT EXISTS nx_support_fund_operation (
 id BIGINT NOT NULL AUTO_INCREMENT,
 op_key VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 business_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 phase VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_key VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 source_environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ordered_events JSON NOT NULL,
 source_snapshot JSON NOT NULL,
 source_proof JSON NOT NULL,
 receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 protocol_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recorded_db_utc DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
 PRIMARY KEY(id),
 UNIQUE KEY uk_support_fund_operation_key(op_key),
 CONSTRAINT ck_support_fund_operation_identity CHECK(char_length(op_key)>0 and char_length(op_key)=char_length(trim(op_key)) and char_length(business_type)>0 and char_length(phase)>0 and char_length(source_table)>0 and char_length(source_key)>0 and char_length(protocol_version)>0),
 CONSTRAINT ck_support_fund_operation_environment CHECK(source_environment='PRODUCTION'),
 CONSTRAINT ck_support_fund_operation_json CHECK(json_type(ordered_events)='ARRAY' and json_type(source_snapshot)='OBJECT' and json_length(source_snapshot)>0 and json_type(source_proof)='OBJECT' and json_length(source_proof)>0),
 CONSTRAINT ck_support_fund_operation_digest CHECK(regexp_like(receipt_sha256,'^[0-9a-f]{64}$','c'))
) ENGINE=InnoDB;
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation')=13
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation'
 AND ((COLUMN_NAME='id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='auto_increment' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='op_key' AND COLUMN_TYPE='varchar(192)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='utf8mb4_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='business_type' AND COLUMN_TYPE='varchar(64)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='ascii_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='phase' AND COLUMN_TYPE='varchar(32)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='ascii_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='source_table' AND COLUMN_TYPE='varchar(64)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='ascii_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='source_key' AND COLUMN_TYPE='varchar(192)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='utf8mb4_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='source_environment' AND COLUMN_TYPE='varchar(16)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='ascii_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='ordered_events' AND COLUMN_TYPE='json' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='source_snapshot' AND COLUMN_TYPE='json' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='source_proof' AND COLUMN_TYPE='json' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='receipt_sha256' AND COLUMN_TYPE='char(64)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='ascii_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='protocol_version' AND COLUMN_TYPE='varchar(64)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='ascii_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='recorded_db_utc' AND COLUMN_TYPE='datetime(6)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='DEFAULT_GENERATED' AND LOWER(REGEXP_REPLACE(COLUMN_DEFAULT,'[()[:space:]]',''))='utc_timestamp6')))=13,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_OPERATION_COLUMNS');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND CONSTRAINT_TYPE='CHECK')=4
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_support_fund_operation' AND tc.ENFORCED='YES'
 AND ((tc.CONSTRAINT_NAME='ck_support_fund_operation_identity' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*(?i:trim)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*[[:space:]]*[)][[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?business_type`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?phase`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?source_table`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?source_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?protocol_version`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*$','c'))
 OR (tc.CONSTRAINT_NAME='ck_support_fund_operation_environment' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*`?source_environment`?[[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''PRODUCTION''[[:space:]()]*$','c'))
 OR (tc.CONSTRAINT_NAME='ck_support_fund_operation_json' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?ordered_events`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''ARRAY''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?source_snapshot`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''OBJECT''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_length)[[:space:]]*[(][[:space:]()]*`?source_snapshot`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?source_proof`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''OBJECT''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_length)[[:space:]]*[(][[:space:]()]*`?source_proof`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*$','c'))
 OR (tc.CONSTRAINT_NAME='ck_support_fund_operation_digest' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*(?i:regexp_like)[[:space:]]*[(][[:space:]()]*`?receipt_sha256`?[[:space:]()]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''[^'']*''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''c''[[:space:]]*[)][[:space:]()]*$','c')
 AND BINARY REGEXP_SUBSTR(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'''[^'']*''')=BINARY '''^[0-9a-f]{64}$''')))=4,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_OPERATION_CHECKS');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;
CREATE TABLE IF NOT EXISTS nx_support_fund_operation_leg (
 operation_id BIGINT NOT NULL,
 wallet_incarnation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 PRIMARY KEY(wallet_incarnation,revision),
 KEY idx_support_fund_operation_leg_operation(operation_id),
 CONSTRAINT ck_support_fund_operation_leg_revision CHECK(revision>=0),
 CONSTRAINT fk_support_fund_operation_leg_operation FOREIGN KEY(operation_id) REFERENCES nx_support_fund_operation(id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation_leg')=3
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation_leg'
 AND ((COLUMN_NAME='operation_id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='wallet_incarnation' AND COLUMN_TYPE='char(36)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='ascii_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='revision' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)))=3,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_OPERATION_LEG_COLUMNS');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND CONSTRAINT_TYPE='CHECK')=1
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_support_fund_operation_leg' AND tc.ENFORCED='YES'
 AND ((tc.CONSTRAINT_NAME='ck_support_fund_operation_leg_revision' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*`?revision`?[[:space:]()]*>=[[:space:]()]*0[[:space:]()]*$','c'))))=1,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_OPERATION_LEG_CHECKS');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND NON_UNIQUE=0)=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND NON_UNIQUE=0 AND SUB_PART IS NULL AND IS_VISIBLE='YES'
 AND ((INDEX_NAME='PRIMARY' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id')
 OR (INDEX_NAME='uk_support_fund_operation_key' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='op_key')))=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND NON_UNIQUE=0)=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND INDEX_NAME='PRIMARY' AND SUB_PART IS NULL AND IS_VISIBLE='YES'
 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME='wallet_incarnation') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME='revision')))=2,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_UNIQUE_SHAPE');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;
SET @fund_operation_sql=IF(NOT EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND REFERENCED_TABLE_NAME IS NOT NULL)
 AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND REFERENCED_TABLE_NAME IS NOT NULL)=1
 AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r
 ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME
 WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME='nx_support_fund_operation_leg'
 AND k.CONSTRAINT_NAME='fk_support_fund_operation_leg_operation' AND k.COLUMN_NAME='operation_id'
 AND k.ORDINAL_POSITION=1 AND k.REFERENCED_TABLE_SCHEMA=DATABASE()
 AND k.REFERENCED_TABLE_NAME='nx_support_fund_operation' AND k.REFERENCED_COLUMN_NAME='id'
 AND r.UPDATE_RULE='RESTRICT' AND r.DELETE_RULE='RESTRICT')=1,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_FOREIGN_KEY_SHAPE');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;

CREATE TRIGGER IF NOT EXISTS nx_support_fund_operation_bu_v1
BEFORE UPDATE ON nx_support_fund_operation FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_FUND_RECORD_IMMUTABLE';
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_fund_operation_bu_v1' AND EVENT_OBJECT_TABLE='nx_support_fund_operation' AND ACTION_TIMING='BEFORE'
 AND EVENT_MANIPULATION='UPDATE' AND BINARY ACTION_STATEMENT=
 BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_OPERATION_BU_V1_SHAPE');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;

CREATE TRIGGER IF NOT EXISTS nx_support_fund_operation_bd_v1
BEFORE DELETE ON nx_support_fund_operation FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_FUND_RECORD_IMMUTABLE';
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_fund_operation_bd_v1' AND EVENT_OBJECT_TABLE='nx_support_fund_operation' AND ACTION_TIMING='BEFORE'
 AND EVENT_MANIPULATION='DELETE' AND BINARY ACTION_STATEMENT=
 BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_OPERATION_BD_V1_SHAPE');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;

CREATE TRIGGER IF NOT EXISTS nx_support_fund_leg_bu_v1
BEFORE UPDATE ON nx_support_fund_operation_leg FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_FUND_RECORD_IMMUTABLE';
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_fund_leg_bu_v1' AND EVENT_OBJECT_TABLE='nx_support_fund_operation_leg' AND ACTION_TIMING='BEFORE'
 AND EVENT_MANIPULATION='UPDATE' AND BINARY ACTION_STATEMENT=
 BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_LEG_BU_V1_SHAPE');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;

CREATE TRIGGER IF NOT EXISTS nx_support_fund_leg_bd_v1
BEFORE DELETE ON nx_support_fund_operation_leg FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_FUND_RECORD_IMMUTABLE';
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_fund_leg_bd_v1' AND EVENT_OBJECT_TABLE='nx_support_fund_operation_leg' AND ACTION_TIMING='BEFORE'
 AND EVENT_MANIPULATION='DELETE' AND BINARY ACTION_STATEMENT=
 BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_NX_SUPPORT_FUND_LEG_BD_V1_SHAPE');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;
SET @fund_operation_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE IN ('nx_support_fund_operation','nx_support_fund_operation_leg'))=4,'SELECT 1','FAIL SUPPORT_FUND_OPERATIONS_TRIGGER_INVENTORY');
PREPARE fund_operation_stmt FROM @fund_operation_sql; EXECUTE fund_operation_stmt; DEALLOCATE PREPARE fund_operation_stmt;
SELECT RELEASE_LOCK(@fund_operation_lock);
