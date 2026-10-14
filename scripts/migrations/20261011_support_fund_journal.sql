-- CHECK patterns preserve quoted literals; trigger bodies compare byte-for-byte and fail closed on format drift.
-- Raw wallet observation only, MySQL 8.0.29+ (same floor as earnings recovery).
-- Source writers must bind every observed revision; these triggers never infer business reasons.
-- Run before API startup with wallet writers stopped, mysql WITHOUT --force. DDL is not transactional.
-- Legacy incarnation seeding requires binlog off or ROW/MIXED; never disable binlog here.
-- A partial initialization may resume only before any journal row or owned trigger exists.
-- Reader gates: require revision 0 NEW_ZERO, contiguous revisions, adjacent full snapshots,
-- current wallet/incarnation/tail equality, no former wallet/customer incarnation, exact live trigger
-- shapes, and complete formal operation legs. Any failure is UNKNOWN, never a zero contribution.
-- No birth rows are backfilled: existing wallets remain UNKNOWN even if currently empty.
-- No FK/cascade: journal survives physical wallet deletion. Missing current wallets and reincarnation
-- are gaps; removal/disable of triggers is a deployment-integrity incident requiring quarantine.
-- shortcut: privileged writes that restore every watched value leave no trace; database write/DDL
-- audit must attest trigger continuity before READY. Re-enable or rerun cannot certify that interval.
SET @fund_lock=CONCAT('nx:fund-journal:',LEFT(SHA2(DATABASE(),256),36));
SELECT GET_LOCK(@fund_lock,10) INTO @fund_locked;
SET @fund_sql=IF(@fund_locked=1,'SELECT 1','CALL support_fund_journal_lock_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;

SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_user_wallet' AND ENGINE='InnoDB')=1,'SELECT 1','CALL support_fund_journal_wallet_engine_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='fund_revision')=0,
 'ALTER TABLE nx_user_wallet ADD COLUMN fund_revision BIGINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_incarnation_missing=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='fund_incarnation')=0;
SET @fund_incarnation_transition=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='fund_incarnation' AND COLUMN_TYPE='char(36)'
 AND CHARACTER_SET_NAME='ascii' AND COLLATION_NAME='ascii_bin' AND IS_NULLABLE IN ('YES','NO')
 AND COLUMN_DEFAULT IS NULL AND EXTRA='' AND COALESCE(GENERATION_EXPRESSION,'')='')=1;
SET @fund_incarnation_ready=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='fund_incarnation' AND COLUMN_TYPE='char(36)'
 AND CHARACTER_SET_NAME='ascii' AND COLLATION_NAME='ascii_bin' AND IS_NULLABLE='NO'
 AND REGEXP_LIKE(COLUMN_DEFAULT,'^[[:space:]()]*[Uu][Uu][Ii][Dd][[:space:]]*[(][[:space:]]*[)][[:space:]()]*$','c')
 AND EXTRA='DEFAULT_GENERATED' AND COALESCE(GENERATION_EXPRESSION,'')='')=1;
SET @fund_existing_journal_rows=0;
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal')=0,'SELECT 1',
 'SELECT COUNT(*) INTO @fund_existing_journal_rows FROM nx_support_wallet_journal');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_existing_journal_triggers=(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME IN ('nx_support_wallet_journal_ai_v1','nx_support_wallet_journal_bu_v1','nx_support_wallet_journal_au_v1'));
SET @fund_sql=IF(@fund_incarnation_ready=1 OR ((@fund_incarnation_missing=1 OR @fund_incarnation_transition=1)
 AND @fund_existing_journal_rows=0 AND @fund_existing_journal_triggers=0
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='fund_revision' AND COLUMN_TYPE='bigint'
 AND IS_NULLABLE='NO' AND COLUMN_DEFAULT='0' AND EXTRA='' AND COALESCE(GENERATION_EXPRESSION,'')='')=1
 AND NOT EXISTS(SELECT 1 FROM nx_user_wallet WHERE fund_revision<>0)
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_user_wallet')=1
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_user_wallet' AND TRIGGER_NAME='nx_wallet_earnings_debit_counter_v1'
 AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE' AND BINARY ACTION_STATEMENT=
 BINARY 'SET NEW.earnings_usdt_debited=OLD.earnings_usdt_debited+GREATEST(OLD.usdt_available-NEW.usdt_available,0),NEW.earnings_nex_debited=OLD.earnings_nex_debited+GREATEST(OLD.nex_available-NEW.nex_available,0)')=1),
 'SELECT 1','FAIL support_fund_journal_incarnation_transition_invalid');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF(@fund_incarnation_ready=1 OR @@GLOBAL.log_bin=0 OR @@SESSION.binlog_format IN ('ROW','MIXED'),
 'SELECT 1','FAIL support_fund_journal_incarnation_binlog_invalid');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF(@fund_incarnation_missing=1,
 'ALTER TABLE nx_user_wallet ADD COLUMN fund_incarnation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL','SELECT 1');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF(@fund_incarnation_missing=1 OR @fund_incarnation_transition=1,
 'UPDATE nx_user_wallet SET fund_incarnation=UUID(),updated_at=updated_at WHERE fund_incarnation IS NULL','SELECT 1');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF(@fund_incarnation_missing=1 OR @fund_incarnation_transition=1,
 'ALTER TABLE nx_user_wallet MODIFY COLUMN fund_incarnation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL','SELECT 1');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF(@fund_incarnation_missing=1 OR @fund_incarnation_transition=1,
 'ALTER TABLE nx_user_wallet ALTER COLUMN fund_incarnation SET DEFAULT (UUID())','SELECT 1');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_user_wallet' AND ((COLUMN_NAME='id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='user_id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='usdt_available' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='nex_available' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='pending_withdraw' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='cregis_risk_held' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='version' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='sandbox' AND DATA_TYPE='tinyint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='is_deleted' AND DATA_TYPE='tinyint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='created_at' AND COLUMN_TYPE='datetime' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='')
  OR (COLUMN_NAME='fund_revision' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT='0' AND EXTRA='')
  OR (COLUMN_NAME='fund_incarnation' AND DATA_TYPE='char' AND CHARACTER_MAXIMUM_LENGTH=36 AND COLLATION_NAME='ascii_bin' AND IS_NULLABLE='NO' AND LOWER(REGEXP_REPLACE(COLUMN_DEFAULT,'[()[:space:]]',''))='uuid' AND EXTRA='DEFAULT_GENERATED')))=12,'SELECT 1','CALL support_fund_journal_wallet_shape_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
CREATE TABLE IF NOT EXISTS nx_support_wallet_journal (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 wallet_incarnation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 observation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 old_id BIGINT NULL,
 new_id BIGINT NOT NULL,
 old_user_id BIGINT NULL,
 new_user_id BIGINT NOT NULL,
 old_usdt_available DECIMAL(18,6) NULL,
 new_usdt_available DECIMAL(18,6) NOT NULL,
 old_nex_available DECIMAL(18,6) NULL,
 new_nex_available DECIMAL(18,6) NOT NULL,
 old_pending_withdraw DECIMAL(18,6) NULL,
 new_pending_withdraw DECIMAL(18,6) NOT NULL,
 old_cregis_risk_held DECIMAL(18,6) NULL,
 new_cregis_risk_held DECIMAL(18,6) NOT NULL,
 old_version BIGINT NULL,
 new_version BIGINT NOT NULL,
 old_sandbox TINYINT NULL,
 new_sandbox TINYINT NOT NULL,
 old_is_deleted TINYINT NULL,
 new_is_deleted TINYINT NOT NULL,
 old_created_at DATETIME NULL,
 new_created_at DATETIME NOT NULL,
 recorded_db_utc DATETIME(6) NOT NULL,
 protocol_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 UNIQUE KEY uk_support_wallet_journal_revision(wallet_incarnation,revision),
 KEY idx_support_wallet_journal_wallet(new_id,id),
 KEY idx_support_wallet_journal_customer(new_user_id,id),
 KEY idx_support_wallet_journal_old_wallet(old_id,id),
 KEY idx_support_wallet_journal_old_customer(old_user_id,id),
 CONSTRAINT ck_support_wallet_journal_revision CHECK(revision>=0),
 CONSTRAINT ck_support_wallet_journal_observation CHECK(observation IN ('NEW_ZERO','NEW_UNPROVEN','CHANGE'))
) ENGINE=InnoDB;
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND ENGINE='InnoDB')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal')=26
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND ((COLUMN_NAME='id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='auto_increment')
  OR (COLUMN_NAME='wallet_incarnation' AND COLUMN_TYPE='char(36)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL AND COLLATION_NAME='ascii_bin')
  OR (COLUMN_NAME='revision' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='observation' AND COLUMN_TYPE='varchar(16)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL AND COLLATION_NAME='ascii_bin')
  OR (COLUMN_NAME='old_id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_user_id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_user_id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_usdt_available' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_usdt_available' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_nex_available' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_nex_available' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_pending_withdraw' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_pending_withdraw' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_cregis_risk_held' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_cregis_risk_held' AND COLUMN_TYPE='decimal(18,6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_version' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_version' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_sandbox' AND DATA_TYPE='tinyint' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_sandbox' AND DATA_TYPE='tinyint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_is_deleted' AND DATA_TYPE='tinyint' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_is_deleted' AND DATA_TYPE='tinyint' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='old_created_at' AND COLUMN_TYPE='datetime' AND IS_NULLABLE='YES' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='new_created_at' AND COLUMN_TYPE='datetime' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='recorded_db_utc' AND COLUMN_TYPE='datetime(6)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME='protocol_sha256' AND COLUMN_TYPE='char(64)' AND IS_NULLABLE='NO' AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL AND COLLATION_NAME='ascii_bin')))=26
 AND NOT EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND REFERENCED_TABLE_NAME IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_support_wallet_journal'),'SELECT 1','CALL support_fund_journal_table_shape_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND NON_UNIQUE=0)=3
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND NON_UNIQUE=0 AND SUB_PART IS NULL AND IS_VISIBLE='YES'
 AND ((INDEX_NAME='PRIMARY' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id')
 OR (INDEX_NAME='uk_support_wallet_journal_revision' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='wallet_incarnation')
 OR (INDEX_NAME='uk_support_wallet_journal_revision' AND SEQ_IN_INDEX=2 AND COLUMN_NAME='revision')))=3,'SELECT 1','CALL support_fund_journal_unique_shape_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;

SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND NON_UNIQUE=1)=8
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND NON_UNIQUE=1 AND SUB_PART IS NULL AND IS_VISIBLE='YES' AND INDEX_TYPE='BTREE'
 AND ((INDEX_NAME='idx_support_wallet_journal_wallet' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='new_id')
 OR (INDEX_NAME='idx_support_wallet_journal_wallet' AND SEQ_IN_INDEX=2 AND COLUMN_NAME='id')
 OR (INDEX_NAME='idx_support_wallet_journal_customer' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='new_user_id')
 OR (INDEX_NAME='idx_support_wallet_journal_customer' AND SEQ_IN_INDEX=2 AND COLUMN_NAME='id')
 OR (INDEX_NAME='idx_support_wallet_journal_old_wallet' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='old_id')
 OR (INDEX_NAME='idx_support_wallet_journal_old_wallet' AND SEQ_IN_INDEX=2 AND COLUMN_NAME='id')
 OR (INDEX_NAME='idx_support_wallet_journal_old_customer' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='old_user_id')
 OR (INDEX_NAME='idx_support_wallet_journal_old_customer' AND SEQ_IN_INDEX=2 AND COLUMN_NAME='id')))=8,
 'SELECT 1','FAIL support_fund_journal_read_index_shape_invalid');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;

DELIMITER $$
CREATE TRIGGER IF NOT EXISTS nx_support_wallet_journal_ai_v1
AFTER INSERT ON nx_user_wallet FOR EACH ROW
BEGIN
 DECLARE prior_incarnation TINYINT;
 SELECT EXISTS(SELECT 1 FROM nx_support_wallet_journal
  WHERE new_id=NEW.id OR old_id=NEW.id
   OR new_user_id=NEW.user_id OR old_user_id=NEW.user_id) INTO prior_incarnation;
 INSERT INTO nx_support_wallet_journal (
  wallet_incarnation, revision, observation, old_id, new_id, old_user_id, new_user_id, old_usdt_available, new_usdt_available, old_nex_available, new_nex_available, old_pending_withdraw, new_pending_withdraw, old_cregis_risk_held, new_cregis_risk_held, old_version, new_version, old_sandbox, new_sandbox, old_is_deleted, new_is_deleted, old_created_at, new_created_at, recorded_db_utc, protocol_sha256
 ) VALUES (
  NEW.fund_incarnation, NEW.fund_revision,
  IF(NEW.fund_revision=0 AND NEW.version=0 AND NEW.usdt_available=0 AND NEW.nex_available=0
   AND NEW.pending_withdraw=0 AND NEW.cregis_risk_held=0 AND NEW.is_deleted=0
   AND prior_incarnation=0, 'NEW_ZERO', 'NEW_UNPROVEN'),
  NULL, NEW.id, NULL, NEW.user_id, NULL, NEW.usdt_available, NULL, NEW.nex_available, NULL, NEW.pending_withdraw, NULL, NEW.cregis_risk_held, NULL, NEW.version, NULL, NEW.sandbox, NULL, NEW.is_deleted, NULL, NEW.created_at, UTC_TIMESTAMP(6), SHA2('SUPPORT_WALLET_JOURNAL_V1',256)
 );
END$$
DELIMITER ;
SET @fund_expected_ai='BEGIN
 DECLARE prior_incarnation TINYINT;
 SELECT EXISTS(SELECT 1 FROM nx_support_wallet_journal
  WHERE new_id=NEW.id OR old_id=NEW.id
   OR new_user_id=NEW.user_id OR old_user_id=NEW.user_id) INTO prior_incarnation;
 INSERT INTO nx_support_wallet_journal (
  wallet_incarnation, revision, observation, old_id, new_id, old_user_id, new_user_id, old_usdt_available, new_usdt_available, old_nex_available, new_nex_available, old_pending_withdraw, new_pending_withdraw, old_cregis_risk_held, new_cregis_risk_held, old_version, new_version, old_sandbox, new_sandbox, old_is_deleted, new_is_deleted, old_created_at, new_created_at, recorded_db_utc, protocol_sha256
 ) VALUES (
  NEW.fund_incarnation, NEW.fund_revision,
  IF(NEW.fund_revision=0 AND NEW.version=0 AND NEW.usdt_available=0 AND NEW.nex_available=0
   AND NEW.pending_withdraw=0 AND NEW.cregis_risk_held=0 AND NEW.is_deleted=0
   AND prior_incarnation=0, ''NEW_ZERO'', ''NEW_UNPROVEN''),
  NULL, NEW.id, NULL, NEW.user_id, NULL, NEW.usdt_available, NULL, NEW.nex_available, NULL, NEW.pending_withdraw, NULL, NEW.cregis_risk_held, NULL, NEW.version, NULL, NEW.sandbox, NULL, NEW.is_deleted, NULL, NEW.created_at, UTC_TIMESTAMP(6), SHA2(''SUPPORT_WALLET_JOURNAL_V1'',256)
 );
END';
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_wallet_journal_ai_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='INSERT'
 AND BINARY ACTION_STATEMENT=BINARY @fund_expected_ai)=1,'SELECT 1','CALL support_fund_journal_trigger_ai_shape_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;

DELIMITER $$
CREATE TRIGGER IF NOT EXISTS nx_support_wallet_journal_bu_v1
BEFORE UPDATE ON nx_user_wallet FOR EACH ROW FOLLOWS nx_wallet_earnings_debit_counter_v1
BEGIN
 IF NOT (NEW.fund_incarnation<=>OLD.fund_incarnation)
   OR NOT (NEW.fund_revision<=>OLD.fund_revision) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_WALLET_JOURNAL_METADATA_CHANGED';
 END IF;
 IF NOT (OLD.id<=>NEW.id)
  OR NOT (OLD.user_id<=>NEW.user_id)
  OR NOT (OLD.usdt_available<=>NEW.usdt_available)
  OR NOT (OLD.nex_available<=>NEW.nex_available)
  OR NOT (OLD.pending_withdraw<=>NEW.pending_withdraw)
  OR NOT (OLD.cregis_risk_held<=>NEW.cregis_risk_held)
  OR NOT (OLD.version<=>NEW.version)
  OR NOT (OLD.sandbox<=>NEW.sandbox)
  OR NOT (OLD.is_deleted<=>NEW.is_deleted)
  OR NOT (OLD.created_at<=>NEW.created_at) THEN
  SET NEW.fund_revision=OLD.fund_revision+1;
 END IF;
END$$
DELIMITER ;
SET @fund_expected_bu='BEGIN
 IF NOT (NEW.fund_incarnation<=>OLD.fund_incarnation)
   OR NOT (NEW.fund_revision<=>OLD.fund_revision) THEN
  SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_WALLET_JOURNAL_METADATA_CHANGED'';
 END IF;
 IF NOT (OLD.id<=>NEW.id)
  OR NOT (OLD.user_id<=>NEW.user_id)
  OR NOT (OLD.usdt_available<=>NEW.usdt_available)
  OR NOT (OLD.nex_available<=>NEW.nex_available)
  OR NOT (OLD.pending_withdraw<=>NEW.pending_withdraw)
  OR NOT (OLD.cregis_risk_held<=>NEW.cregis_risk_held)
  OR NOT (OLD.version<=>NEW.version)
  OR NOT (OLD.sandbox<=>NEW.sandbox)
  OR NOT (OLD.is_deleted<=>NEW.is_deleted)
  OR NOT (OLD.created_at<=>NEW.created_at) THEN
  SET NEW.fund_revision=OLD.fund_revision+1;
 END IF;
END';
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_wallet_journal_bu_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE'
 AND BINARY ACTION_STATEMENT=BINARY @fund_expected_bu)=1,'SELECT 1','CALL support_fund_journal_trigger_bu_shape_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;

DELIMITER $$
CREATE TRIGGER IF NOT EXISTS nx_support_wallet_journal_au_v1
AFTER UPDATE ON nx_user_wallet FOR EACH ROW
BEGIN
 IF NEW.fund_revision<>OLD.fund_revision THEN
  INSERT INTO nx_support_wallet_journal (
   wallet_incarnation, revision, observation, old_id, new_id, old_user_id, new_user_id, old_usdt_available, new_usdt_available, old_nex_available, new_nex_available, old_pending_withdraw, new_pending_withdraw, old_cregis_risk_held, new_cregis_risk_held, old_version, new_version, old_sandbox, new_sandbox, old_is_deleted, new_is_deleted, old_created_at, new_created_at, recorded_db_utc, protocol_sha256
  ) VALUES (
   NEW.fund_incarnation, NEW.fund_revision, 'CHANGE',
   OLD.id, NEW.id, OLD.user_id, NEW.user_id, OLD.usdt_available, NEW.usdt_available, OLD.nex_available, NEW.nex_available, OLD.pending_withdraw, NEW.pending_withdraw, OLD.cregis_risk_held, NEW.cregis_risk_held, OLD.version, NEW.version, OLD.sandbox, NEW.sandbox, OLD.is_deleted, NEW.is_deleted, OLD.created_at, NEW.created_at, UTC_TIMESTAMP(6), SHA2('SUPPORT_WALLET_JOURNAL_V1',256)
  );
 END IF;
END$$
DELIMITER ;
SET @fund_expected_au='BEGIN
 IF NEW.fund_revision<>OLD.fund_revision THEN
  INSERT INTO nx_support_wallet_journal (
   wallet_incarnation, revision, observation, old_id, new_id, old_user_id, new_user_id, old_usdt_available, new_usdt_available, old_nex_available, new_nex_available, old_pending_withdraw, new_pending_withdraw, old_cregis_risk_held, new_cregis_risk_held, old_version, new_version, old_sandbox, new_sandbox, old_is_deleted, new_is_deleted, old_created_at, new_created_at, recorded_db_utc, protocol_sha256
  ) VALUES (
   NEW.fund_incarnation, NEW.fund_revision, ''CHANGE'',
   OLD.id, NEW.id, OLD.user_id, NEW.user_id, OLD.usdt_available, NEW.usdt_available, OLD.nex_available, NEW.nex_available, OLD.pending_withdraw, NEW.pending_withdraw, OLD.cregis_risk_held, NEW.cregis_risk_held, OLD.version, NEW.version, OLD.sandbox, NEW.sandbox, OLD.is_deleted, NEW.is_deleted, OLD.created_at, NEW.created_at, UTC_TIMESTAMP(6), SHA2(''SUPPORT_WALLET_JOURNAL_V1'',256)
  );
 END IF;
END';
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_wallet_journal_au_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='UPDATE'
 AND BINARY ACTION_STATEMENT=BINARY @fund_expected_au)=1,'SELECT 1','CALL support_fund_journal_trigger_au_shape_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_user_wallet')=4
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_user_wallet' AND TRIGGER_NAME IN ('nx_wallet_earnings_debit_counter_v1',
 'nx_support_wallet_journal_ai_v1','nx_support_wallet_journal_bu_v1','nx_support_wallet_journal_au_v1'))=4,
 'SELECT 1','FAIL support_fund_journal_wallet_trigger_inventory_invalid');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_user_wallet' AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE')=2
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_wallet_earnings_debit_counter_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE'
 AND BINARY ACTION_STATEMENT=
 BINARY 'SET NEW.earnings_usdt_debited=OLD.earnings_usdt_debited+GREATEST(OLD.usdt_available-NEW.usdt_available,0),NEW.earnings_nex_debited=OLD.earnings_nex_debited+GREATEST(OLD.nex_available-NEW.nex_available,0)')=1
 AND (SELECT ACTION_ORDER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_wallet_journal_bu_v1')>
 (SELECT ACTION_ORDER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_wallet_earnings_debit_counter_v1'),'SELECT 1','CALL support_fund_journal_wallet_trigger_order_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SET @fund_sql=IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND CONSTRAINT_TYPE='CHECK')=2
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc
 JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA
 AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_support_wallet_journal' AND tc.ENFORCED='YES'
 AND ((tc.CONSTRAINT_NAME='ck_support_wallet_journal_revision'
 AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*`?revision`?[[:space:]()]*>=[[:space:]()]*0[[:space:]()]*$','c'))
 OR (tc.CONSTRAINT_NAME='ck_support_wallet_journal_observation'
 AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*`?observation`?[[:space:]()]*(?i:in)[[:space:]]*[(][[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''NEW_ZERO''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''NEW_UNPROVEN''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''CHANGE''[[:space:]]*[)][[:space:]()]*$','c'))))=2,'SELECT 1','CALL support_fund_journal_check_shape_invalid()');
PREPARE fund_stmt FROM @fund_sql; EXECUTE fund_stmt; DEALLOCATE PREPARE fund_stmt;
SELECT RELEASE_LOCK(@fund_lock);
