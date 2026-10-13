-- Immutable participation from the locked writer scope, including zero-event/zero-cash receipts.
-- MySQL 8.0.29+. DDL is not transactional: stop writers, mysql WITHOUT --force, before API startup.
-- No historical backfill; an old receipt without parties is not certified replay.
-- APP grants on this table are SELECT/INSERT only; no wallet/customer FK or history mutation.
SET @fund_party_lock=CONCAT('nx:fund-parties:',LEFT(SHA2(DATABASE(),256),36));
SELECT GET_LOCK(@fund_party_lock,10) INTO @fund_party_locked;
SET @fund_party_sql=IF(@fund_party_locked=1,'SELECT 1','FAIL SUPPORT_FUND_PARTY_LOCK');
PREPARE fund_party_stmt FROM @fund_party_sql; EXECUTE fund_party_stmt; DEALLOCATE PREPARE fund_party_stmt;

CREATE TABLE IF NOT EXISTS nx_support_fund_operation_party (
 operation_id BIGINT NOT NULL,
 customer_id BIGINT NOT NULL,
 PRIMARY KEY(operation_id,customer_id),
 KEY idx_support_fund_party_customer(customer_id,operation_id),
 CONSTRAINT ck_support_fund_party_customer CHECK(customer_id>0),
 CONSTRAINT fk_support_fund_party_operation FOREIGN KEY(operation_id) REFERENCES nx_support_fund_operation(id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

SET @fund_party_sql=IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation_party')=2
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation_party'
 AND COLUMN_NAME IN ('operation_id','customer_id') AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO'
 AND COLUMN_DEFAULT IS NULL AND EXTRA='' AND COALESCE(GENERATION_EXPRESSION,'')='')=2,'SELECT 1','FAIL SUPPORT_FUND_PARTY_COLUMNS');
PREPARE fund_party_stmt FROM @fund_party_sql; EXECUTE fund_party_stmt; DEALLOCATE PREPARE fund_party_stmt;

SET @fund_party_sql=IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party')=3
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party' AND CONSTRAINT_NAME='PRIMARY' AND CONSTRAINT_TYPE='PRIMARY KEY')=1
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_support_fund_operation_party'
 AND tc.CONSTRAINT_NAME='ck_support_fund_party_customer' AND tc.CONSTRAINT_TYPE='CHECK' AND tc.ENFORCED='YES'
 AND REGEXP_LIKE(cc.CHECK_CLAUSE,'^[[:space:]()]*`?customer_id`?[[:space:]()]*>[[:space:]()]*0[[:space:]()]*$','c'))=1,'SELECT 1','FAIL SUPPORT_FUND_PARTY_CONSTRAINTS');
PREPARE fund_party_stmt FROM @fund_party_sql; EXECUTE fund_party_stmt; DEALLOCATE PREPARE fund_party_stmt;

SET @fund_party_sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party')=4
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party' AND INDEX_TYPE='BTREE' AND IS_VISIBLE='YES' AND SUB_PART IS NULL
 AND EXPRESSION IS NULL AND COLLATION='A'
 AND ((INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME='operation_id') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME='customer_id')))
 OR (INDEX_NAME='idx_support_fund_party_customer' AND NON_UNIQUE=1 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME='customer_id') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME='operation_id')))))=4,'SELECT 1','FAIL SUPPORT_FUND_PARTY_INDEXES');
PREPARE fund_party_stmt FROM @fund_party_sql; EXECUTE fund_party_stmt; DEALLOCATE PREPARE fund_party_stmt;

SET @fund_party_sql=IF((SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party' AND REFERENCED_TABLE_NAME IS NOT NULL)=1
 AND (SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party')=1
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party' AND CONSTRAINT_NAME='fk_support_fund_party_operation' AND CONSTRAINT_TYPE='FOREIGN KEY')=1
 AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r
 ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND r.TABLE_NAME=k.TABLE_NAME
 WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME='nx_support_fund_operation_party'
 AND k.CONSTRAINT_NAME='fk_support_fund_party_operation' AND k.COLUMN_NAME='operation_id' AND k.ORDINAL_POSITION=1
 AND k.POSITION_IN_UNIQUE_CONSTRAINT=1 AND k.REFERENCED_TABLE_SCHEMA=DATABASE()
 AND k.REFERENCED_TABLE_NAME='nx_support_fund_operation' AND k.REFERENCED_COLUMN_NAME='id'
 AND r.UNIQUE_CONSTRAINT_SCHEMA=DATABASE() AND r.UNIQUE_CONSTRAINT_NAME='PRIMARY'
 AND r.MATCH_OPTION='NONE' AND r.UPDATE_RULE='RESTRICT' AND r.DELETE_RULE='RESTRICT')=1,'SELECT 1','FAIL SUPPORT_FUND_PARTY_FOREIGN_KEY');
PREPARE fund_party_stmt FROM @fund_party_sql; EXECUTE fund_party_stmt; DEALLOCATE PREPARE fund_party_stmt;

CREATE TRIGGER IF NOT EXISTS nx_support_fund_party_bu_v1
BEFORE UPDATE ON nx_support_fund_operation_party FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_FUND_RECORD_IMMUTABLE';
CREATE TRIGGER IF NOT EXISTS nx_support_fund_party_bd_v1
BEFORE DELETE ON nx_support_fund_operation_party FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_FUND_RECORD_IMMUTABLE';
SET @fund_party_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=2
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party' AND EVENT_OBJECT_SCHEMA=DATABASE()
 AND ACTION_TIMING='BEFORE' AND ACTION_ORIENTATION='ROW' AND ACTION_ORDER=1
 AND ((TRIGGER_NAME='nx_support_fund_party_bu_v1' AND EVENT_MANIPULATION='UPDATE')
 OR (TRIGGER_NAME='nx_support_fund_party_bd_v1' AND EVENT_MANIPULATION='DELETE'))
 AND BINARY ACTION_STATEMENT=BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=2,'SELECT 1','FAIL SUPPORT_FUND_PARTY_TRIGGERS');
PREPARE fund_party_stmt FROM @fund_party_sql; EXECUTE fund_party_stmt; DEALLOCATE PREPARE fund_party_stmt;
SELECT RELEASE_LOCK(@fund_party_lock);
