-- Fixed no-argument metadata reader, MySQL 8.0.29+; install after the original fund three.
-- Install with the independently approved schema/admin principal, not the restricted APP.
-- This is a live schema check, not historical trigger-coverage attestation.
-- APP receives only the separate, explicitly approved EXECUTE grant for this function.
-- The CREATE body remains byte-for-byte equal to the canonical Java business guard.
-- Expected ROUTINE_DEFINITION projects each raw literal by decoding doubled apostrophes,
-- preserves its outer quotes and all outside bytes, then SQL-quotes that metadata text.
-- The fixed source has no backslash escapes; existing/installed shapes still compare in full.
-- No wallet, receipt, proof, cash or historical birth rows are written here.
SET @fund_schema_reader_lock=CONCAT('nx:fund-schema-reader:',LEFT(SHA2(DATABASE(),256),36));
SELECT GET_LOCK(@fund_schema_reader_lock,10) INTO @fund_schema_reader_locked;
SET @fund_schema_reader_sql=IF(@fund_schema_reader_locked=1,'SELECT 1','FAIL SUPPORT_FUND_SCHEMA_READER_LOCK');
PREPARE fund_schema_reader_stmt FROM @fund_schema_reader_sql; EXECUTE fund_schema_reader_stmt; DEALLOCATE PREPARE fund_schema_reader_stmt;

SET @fund_schema_reader_expected_body='RETURN (
SELECT (
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_user_wallet'' AND ENGINE=''InnoDB'')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_user_wallet'' AND ((COLUMN_NAME=''id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''user_id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''usdt_available'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''nex_available'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''pending_withdraw'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''cregis_risk_held'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''version'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''sandbox'' AND DATA_TYPE=''tinyint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''is_deleted'' AND DATA_TYPE=''tinyint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''created_at'' AND COLUMN_TYPE=''datetime'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''')
  OR (COLUMN_NAME=''fund_revision'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COLUMN_DEFAULT=''0'' AND EXTRA='''')
  OR (COLUMN_NAME=''fund_incarnation'' AND DATA_TYPE=''char'' AND CHARACTER_MAXIMUM_LENGTH=36 AND COLLATION_NAME=''ascii_bin'' AND IS_NULLABLE=''NO'' AND LOWER(REGEXP_REPLACE(COLUMN_DEFAULT,''[()[:space:]]'',''''))=''uuid'' AND EXTRA=''DEFAULT_GENERATED'')))=12)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'' AND ENGINE=''InnoDB'')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'')=26
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'' AND ((COLUMN_NAME=''id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA=''auto_increment'')
  OR (COLUMN_NAME=''wallet_incarnation'' AND COLUMN_TYPE=''char(36)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL AND COLLATION_NAME=''ascii_bin'')
  OR (COLUMN_NAME=''revision'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''observation'' AND COLUMN_TYPE=''varchar(16)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL AND COLLATION_NAME=''ascii_bin'')
  OR (COLUMN_NAME=''old_id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_user_id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_user_id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_usdt_available'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_usdt_available'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_nex_available'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_nex_available'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_pending_withdraw'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_pending_withdraw'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_cregis_risk_held'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_cregis_risk_held'' AND COLUMN_TYPE=''decimal(18,6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_version'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_version'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_sandbox'' AND DATA_TYPE=''tinyint'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_sandbox'' AND DATA_TYPE=''tinyint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_is_deleted'' AND DATA_TYPE=''tinyint'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_is_deleted'' AND DATA_TYPE=''tinyint'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''old_created_at'' AND COLUMN_TYPE=''datetime'' AND IS_NULLABLE=''YES'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''new_created_at'' AND COLUMN_TYPE=''datetime'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''recorded_db_utc'' AND COLUMN_TYPE=''datetime(6)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
  OR (COLUMN_NAME=''protocol_sha256'' AND COLUMN_TYPE=''char(64)'' AND IS_NULLABLE=''NO'' AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL AND COLLATION_NAME=''ascii_bin'')))=26
 AND NOT EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'' AND REFERENCED_TABLE_NAME IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE=''nx_support_wallet_journal''))
          AND
          ((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'' AND NON_UNIQUE=0)=3
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'' AND NON_UNIQUE=0 AND SUB_PART IS NULL AND IS_VISIBLE=''YES''
 AND ((INDEX_NAME=''PRIMARY'' AND SEQ_IN_INDEX=1 AND COLUMN_NAME=''id'')
 OR (INDEX_NAME=''uk_support_wallet_journal_revision'' AND SEQ_IN_INDEX=1 AND COLUMN_NAME=''wallet_incarnation'')
 OR (INDEX_NAME=''uk_support_wallet_journal_revision'' AND SEQ_IN_INDEX=2 AND COLUMN_NAME=''revision'')))=3)
          AND
          ((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'' AND NON_UNIQUE=1)=8
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'' AND NON_UNIQUE=1 AND SUB_PART IS NULL AND IS_VISIBLE=''YES'' AND INDEX_TYPE=''BTREE''
 AND ((INDEX_NAME=''idx_support_wallet_journal_wallet'' AND SEQ_IN_INDEX=1 AND COLUMN_NAME=''new_id'')
 OR (INDEX_NAME=''idx_support_wallet_journal_wallet'' AND SEQ_IN_INDEX=2 AND COLUMN_NAME=''id'')
 OR (INDEX_NAME=''idx_support_wallet_journal_customer'' AND SEQ_IN_INDEX=1 AND COLUMN_NAME=''new_user_id'')
 OR (INDEX_NAME=''idx_support_wallet_journal_customer'' AND SEQ_IN_INDEX=2 AND COLUMN_NAME=''id'')
 OR (INDEX_NAME=''idx_support_wallet_journal_old_wallet'' AND SEQ_IN_INDEX=1 AND COLUMN_NAME=''old_id'')
 OR (INDEX_NAME=''idx_support_wallet_journal_old_wallet'' AND SEQ_IN_INDEX=2 AND COLUMN_NAME=''id'')
 OR (INDEX_NAME=''idx_support_wallet_journal_old_customer'' AND SEQ_IN_INDEX=1 AND COLUMN_NAME=''old_user_id'')
 OR (INDEX_NAME=''idx_support_wallet_journal_old_customer'' AND SEQ_IN_INDEX=2 AND COLUMN_NAME=''id'')))=8)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_support_wallet_journal_ai_v1'' AND EVENT_OBJECT_TABLE=''nx_user_wallet''
 AND ACTION_TIMING=''AFTER'' AND EVENT_MANIPULATION=''INSERT''
 AND BINARY ACTION_STATEMENT=BINARY ''BEGIN
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
END'')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_support_wallet_journal_bu_v1'' AND EVENT_OBJECT_TABLE=''nx_user_wallet''
 AND ACTION_TIMING=''BEFORE'' AND EVENT_MANIPULATION=''UPDATE''
 AND BINARY ACTION_STATEMENT=BINARY ''BEGIN
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
END'')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_support_wallet_journal_au_v1'' AND EVENT_OBJECT_TABLE=''nx_user_wallet''
 AND ACTION_TIMING=''AFTER'' AND EVENT_MANIPULATION=''UPDATE''
 AND BINARY ACTION_STATEMENT=BINARY ''BEGIN
 IF NEW.fund_revision<>OLD.fund_revision THEN
  INSERT INTO nx_support_wallet_journal (
   wallet_incarnation, revision, observation, old_id, new_id, old_user_id, new_user_id, old_usdt_available, new_usdt_available, old_nex_available, new_nex_available, old_pending_withdraw, new_pending_withdraw, old_cregis_risk_held, new_cregis_risk_held, old_version, new_version, old_sandbox, new_sandbox, old_is_deleted, new_is_deleted, old_created_at, new_created_at, recorded_db_utc, protocol_sha256
  ) VALUES (
   NEW.fund_incarnation, NEW.fund_revision, ''CHANGE'',
   OLD.id, NEW.id, OLD.user_id, NEW.user_id, OLD.usdt_available, NEW.usdt_available, OLD.nex_available, NEW.nex_available, OLD.pending_withdraw, NEW.pending_withdraw, OLD.cregis_risk_held, NEW.cregis_risk_held, OLD.version, NEW.version, OLD.sandbox, NEW.sandbox, OLD.is_deleted, NEW.is_deleted, OLD.created_at, NEW.created_at, UTC_TIMESTAMP(6), SHA2(''SUPPORT_WALLET_JOURNAL_V1'',256)
  );
 END IF;
END'')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE=''nx_user_wallet'')=4
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE=''nx_user_wallet'' AND TRIGGER_NAME IN (''nx_wallet_earnings_debit_counter_v1'',
 ''nx_support_wallet_journal_ai_v1'',''nx_support_wallet_journal_bu_v1'',''nx_support_wallet_journal_au_v1''))=4)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE=''nx_user_wallet'' AND ACTION_TIMING=''BEFORE'' AND EVENT_MANIPULATION=''UPDATE'')=2
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_wallet_earnings_debit_counter_v1'' AND EVENT_OBJECT_TABLE=''nx_user_wallet''
 AND ACTION_TIMING=''BEFORE'' AND EVENT_MANIPULATION=''UPDATE''
 AND BINARY ACTION_STATEMENT=
 BINARY ''SET NEW.earnings_usdt_debited=OLD.earnings_usdt_debited+GREATEST(OLD.usdt_available-NEW.usdt_available,0),NEW.earnings_nex_debited=OLD.earnings_nex_debited+GREATEST(OLD.nex_available-NEW.nex_available,0)'')=1
 AND (SELECT ACTION_ORDER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_support_wallet_journal_bu_v1'')>
 (SELECT ACTION_ORDER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_wallet_earnings_debit_counter_v1''))
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_wallet_journal'' AND CONSTRAINT_TYPE=''CHECK'')=2
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc
 JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA
 AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME=''nx_support_wallet_journal'' AND tc.ENFORCED=''YES''
 AND ((tc.CONSTRAINT_NAME=''ck_support_wallet_journal_revision''
 AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),''^[[:space:]()]*`?revision`?[[:space:]()]*>=[[:space:]()]*0[[:space:]()]*$'',''c''))
 OR (tc.CONSTRAINT_NAME=''ck_support_wallet_journal_observation''
 AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),''^[[:space:]()]*`?observation`?[[:space:]()]*(?i:in)[[:space:]]*[(][[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''NEW_ZERO''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''NEW_UNPROVEN''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''CHANGE''[[:space:]]*[)][[:space:]()]*$'',''c''))))=2)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation'' AND TABLE_TYPE=''BASE TABLE'' AND ENGINE=''InnoDB'')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=''nx_support_fund_operation'')=13
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=''nx_support_fund_operation''
 AND ((COLUMN_NAME=''id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA=''auto_increment'' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''op_key'' AND COLUMN_TYPE=''varchar(192)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''utf8mb4_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''business_type'' AND COLUMN_TYPE=''varchar(64)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''ascii_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''phase'' AND COLUMN_TYPE=''varchar(32)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''ascii_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''source_table'' AND COLUMN_TYPE=''varchar(64)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''ascii_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''source_key'' AND COLUMN_TYPE=''varchar(192)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''utf8mb4_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''source_environment'' AND COLUMN_TYPE=''varchar(16)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''ascii_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''ordered_events'' AND COLUMN_TYPE=''json'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''source_snapshot'' AND COLUMN_TYPE=''json'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''source_proof'' AND COLUMN_TYPE=''json'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''receipt_sha256'' AND COLUMN_TYPE=''char(64)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''ascii_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''protocol_version'' AND COLUMN_TYPE=''varchar(64)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''ascii_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''recorded_db_utc'' AND COLUMN_TYPE=''datetime(6)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA=''DEFAULT_GENERATED'' AND LOWER(REGEXP_REPLACE(COLUMN_DEFAULT,''[()[:space:]]'',''''))=''utc_timestamp6'')))=13)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation'' AND CONSTRAINT_TYPE=''CHECK'')=4
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME=''nx_support_fund_operation'' AND tc.ENFORCED=''YES''
 AND ((tc.CONSTRAINT_NAME=''ck_support_fund_operation_identity'' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),''^[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*(?i:trim)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*[[:space:]]*[)][[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?business_type`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?phase`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?source_table`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?source_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?protocol_version`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*$'',''c''))
 OR (tc.CONSTRAINT_NAME=''ck_support_fund_operation_environment'' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),''^[[:space:]()]*`?source_environment`?[[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''PRODUCTION''[[:space:]()]*$'',''c''))
 OR (tc.CONSTRAINT_NAME=''ck_support_fund_operation_json'' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),''^[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?ordered_events`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''ARRAY''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?source_snapshot`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''OBJECT''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_length)[[:space:]]*[(][[:space:]()]*`?source_snapshot`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?source_proof`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''OBJECT''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_length)[[:space:]]*[(][[:space:]()]*`?source_proof`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*$'',''c''))
 OR (tc.CONSTRAINT_NAME=''ck_support_fund_operation_digest'' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),''^[[:space:]()]*(?i:regexp_like)[[:space:]]*[(][[:space:]()]*`?receipt_sha256`?[[:space:]()]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''[^'']*''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''c''[[:space:]]*[)][[:space:]()]*$'',''c'')
 AND BINARY REGEXP_SUBSTR(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),''''[^'']*'''')=BINARY ''''^[0-9a-f]{64}$'''')))=4)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_leg'' AND TABLE_TYPE=''BASE TABLE'' AND ENGINE=''InnoDB'')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=''nx_support_fund_operation_leg'')=3
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=''nx_support_fund_operation_leg''
 AND ((COLUMN_NAME=''operation_id'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''wallet_incarnation'' AND COLUMN_TYPE=''char(36)'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND COLLATION_NAME=''ascii_bin'' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME=''revision'' AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO''
  AND COALESCE(GENERATION_EXPRESSION,'''')='''' AND EXTRA='''' AND COLUMN_DEFAULT IS NULL)))=3)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_leg'' AND CONSTRAINT_TYPE=''CHECK'')=1
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME=''nx_support_fund_operation_leg'' AND tc.ENFORCED=''YES''
 AND ((tc.CONSTRAINT_NAME=''ck_support_fund_operation_leg_revision'' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),''^[[:space:]()]*`?revision`?[[:space:]()]*>=[[:space:]()]*0[[:space:]()]*$'',''c''))))=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation'' AND NON_UNIQUE=0)=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation'' AND NON_UNIQUE=0 AND SUB_PART IS NULL AND IS_VISIBLE=''YES''
 AND ((INDEX_NAME=''PRIMARY'' AND SEQ_IN_INDEX=1 AND COLUMN_NAME=''id'')
 OR (INDEX_NAME=''uk_support_fund_operation_key'' AND SEQ_IN_INDEX=1 AND COLUMN_NAME=''op_key'')))=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_leg'' AND NON_UNIQUE=0)=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_leg'' AND INDEX_NAME=''PRIMARY'' AND SUB_PART IS NULL AND IS_VISIBLE=''YES''
 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME=''wallet_incarnation'') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME=''revision'')))=2)
          AND
          (NOT EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation'' AND REFERENCED_TABLE_NAME IS NOT NULL)
 AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_leg'' AND REFERENCED_TABLE_NAME IS NOT NULL)=1
 AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r
 ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME
 WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME=''nx_support_fund_operation_leg''
 AND k.CONSTRAINT_NAME=''fk_support_fund_operation_leg_operation'' AND k.COLUMN_NAME=''operation_id''
 AND k.ORDINAL_POSITION=1 AND k.REFERENCED_TABLE_SCHEMA=DATABASE()
 AND k.REFERENCED_TABLE_NAME=''nx_support_fund_operation'' AND k.REFERENCED_COLUMN_NAME=''id''
 AND r.UPDATE_RULE=''RESTRICT'' AND r.DELETE_RULE=''RESTRICT'')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_support_fund_operation_bu_v1'' AND EVENT_OBJECT_TABLE=''nx_support_fund_operation'' AND ACTION_TIMING=''BEFORE''
 AND EVENT_MANIPULATION=''UPDATE'' AND BINARY ACTION_STATEMENT=
 BINARY ''SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE'''')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_support_fund_operation_bd_v1'' AND EVENT_OBJECT_TABLE=''nx_support_fund_operation'' AND ACTION_TIMING=''BEFORE''
 AND EVENT_MANIPULATION=''DELETE'' AND BINARY ACTION_STATEMENT=
 BINARY ''SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE'''')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_support_fund_leg_bu_v1'' AND EVENT_OBJECT_TABLE=''nx_support_fund_operation_leg'' AND ACTION_TIMING=''BEFORE''
 AND EVENT_MANIPULATION=''UPDATE'' AND BINARY ACTION_STATEMENT=
 BINARY ''SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE'''')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME=''nx_support_fund_leg_bd_v1'' AND EVENT_OBJECT_TABLE=''nx_support_fund_operation_leg'' AND ACTION_TIMING=''BEFORE''
 AND EVENT_MANIPULATION=''DELETE'' AND BINARY ACTION_STATEMENT=
 BINARY ''SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE'''')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE IN (''nx_support_fund_operation'',''nx_support_fund_operation_leg''))=4)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_party'' AND TABLE_TYPE=''BASE TABLE'' AND ENGINE=''InnoDB'')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=''nx_support_fund_operation_party'')=2
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=''nx_support_fund_operation_party''
 AND COLUMN_NAME IN (''operation_id'',''customer_id'') AND COLUMN_TYPE=''bigint'' AND IS_NULLABLE=''NO''
 AND COLUMN_DEFAULT IS NULL AND EXTRA='''' AND COALESCE(GENERATION_EXPRESSION,'''')='''')=2)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_party'')=3
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_party'' AND CONSTRAINT_NAME=''PRIMARY'' AND CONSTRAINT_TYPE=''PRIMARY KEY'')=1
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME=''nx_support_fund_operation_party''
 AND tc.CONSTRAINT_NAME=''ck_support_fund_party_customer'' AND tc.CONSTRAINT_TYPE=''CHECK'' AND tc.ENFORCED=''YES''
 AND REGEXP_LIKE(cc.CHECK_CLAUSE,''^[[:space:]()]*`?customer_id`?[[:space:]()]*>[[:space:]()]*0[[:space:]()]*$'',''c''))=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_party'')=4
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_party'' AND INDEX_TYPE=''BTREE'' AND IS_VISIBLE=''YES'' AND SUB_PART IS NULL
 AND EXPRESSION IS NULL AND COLLATION=''A''
 AND ((INDEX_NAME=''PRIMARY'' AND NON_UNIQUE=0 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME=''operation_id'') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME=''customer_id'')))
 OR (INDEX_NAME=''idx_support_fund_party_customer'' AND NON_UNIQUE=1 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME=''customer_id'') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME=''operation_id'')))))=4)
          AND
          ((SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_party'' AND REFERENCED_TABLE_NAME IS NOT NULL)=1
 AND (SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_party'')=1
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME=''nx_support_fund_operation_party'' AND CONSTRAINT_NAME=''fk_support_fund_party_operation'' AND CONSTRAINT_TYPE=''FOREIGN KEY'')=1
 AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r
 ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND r.TABLE_NAME=k.TABLE_NAME
 WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME=''nx_support_fund_operation_party''
 AND k.CONSTRAINT_NAME=''fk_support_fund_party_operation'' AND k.COLUMN_NAME=''operation_id'' AND k.ORDINAL_POSITION=1
 AND k.POSITION_IN_UNIQUE_CONSTRAINT=1 AND k.REFERENCED_TABLE_SCHEMA=DATABASE()
 AND k.REFERENCED_TABLE_NAME=''nx_support_fund_operation'' AND k.REFERENCED_COLUMN_NAME=''id''
 AND r.UNIQUE_CONSTRAINT_SCHEMA=DATABASE() AND r.UNIQUE_CONSTRAINT_NAME=''PRIMARY''
 AND r.MATCH_OPTION=''NONE'' AND r.UPDATE_RULE=''RESTRICT'' AND r.DELETE_RULE=''RESTRICT'')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE=''nx_support_fund_operation_party'')=2
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE=''nx_support_fund_operation_party'' AND EVENT_OBJECT_SCHEMA=DATABASE()
 AND ACTION_TIMING=''BEFORE'' AND ACTION_ORIENTATION=''ROW'' AND ACTION_ORDER=1
 AND ((TRIGGER_NAME=''nx_support_fund_party_bu_v1'' AND EVENT_MANIPULATION=''UPDATE'')
 OR (TRIGGER_NAME=''nx_support_fund_party_bd_v1'' AND EVENT_MANIPULATION=''DELETE''))
 AND BINARY ACTION_STATEMENT=BINARY ''SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE'''')=2)
        )
)';
SET @fund_schema_reader_existing=(SELECT COUNT(*) FROM information_schema.ROUTINES
 WHERE ROUTINE_SCHEMA = DATABASE() AND ROUTINE_NAME='nx_support_fund_schema_ready_v1');
SET @fund_schema_reader_existing_ok=((SELECT COUNT(*) FROM information_schema.ROUTINES
 WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='nx_support_fund_schema_ready_v1'
 AND ROUTINE_TYPE='FUNCTION' AND DATA_TYPE='tinyint' AND DTD_IDENTIFIER='tinyint unsigned'
 AND IS_DETERMINISTIC='NO' AND SQL_DATA_ACCESS='READS SQL DATA'
 AND SECURITY_TYPE='DEFINER' AND DEFINER=CURRENT_USER()
 AND BINARY ROUTINE_DEFINITION=BINARY @fund_schema_reader_expected_body)=1
 AND NOT EXISTS(SELECT 1 FROM information_schema.PARAMETERS
 WHERE SPECIFIC_SCHEMA=DATABASE() AND SPECIFIC_NAME='nx_support_fund_schema_ready_v1' AND ORDINAL_POSITION>0));
SET @fund_schema_reader_sql=IF(@fund_schema_reader_existing=0 OR (@fund_schema_reader_existing=1 AND @fund_schema_reader_existing_ok=1),
 'SELECT 1','FAIL SUPPORT_FUND_SCHEMA_READER_EXISTING_SHAPE');
PREPARE fund_schema_reader_stmt FROM @fund_schema_reader_sql; EXECUTE fund_schema_reader_stmt; DEALLOCATE PREPARE fund_schema_reader_stmt;

CREATE DEFINER=CURRENT_USER FUNCTION IF NOT EXISTS nx_support_fund_schema_ready_v1()
RETURNS TINYINT UNSIGNED
NOT DETERMINISTIC
READS SQL DATA
SQL SECURITY DEFINER
-- FUNCTION_BODY_BEGIN
RETURN (
SELECT (
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_user_wallet' AND ENGINE='InnoDB')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
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
  OR (COLUMN_NAME='fund_incarnation' AND DATA_TYPE='char' AND CHARACTER_MAXIMUM_LENGTH=36 AND COLLATION_NAME='ascii_bin' AND IS_NULLABLE='NO' AND LOWER(REGEXP_REPLACE(COLUMN_DEFAULT,'[()[:space:]]',''))='uuid' AND EXTRA='DEFAULT_GENERATED')))=12)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
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
 AND EVENT_OBJECT_TABLE='nx_support_wallet_journal'))
          AND
          ((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND NON_UNIQUE=0)=3
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND NON_UNIQUE=0 AND SUB_PART IS NULL AND IS_VISIBLE='YES'
 AND ((INDEX_NAME='PRIMARY' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id')
 OR (INDEX_NAME='uk_support_wallet_journal_revision' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='wallet_incarnation')
 OR (INDEX_NAME='uk_support_wallet_journal_revision' AND SEQ_IN_INDEX=2 AND COLUMN_NAME='revision')))=3)
          AND
          ((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
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
 OR (INDEX_NAME='idx_support_wallet_journal_old_customer' AND SEQ_IN_INDEX=2 AND COLUMN_NAME='id')))=8)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_wallet_journal_ai_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='INSERT'
 AND BINARY ACTION_STATEMENT=BINARY 'BEGIN
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
END')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_wallet_journal_bu_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE'
 AND BINARY ACTION_STATEMENT=BINARY 'BEGIN
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
END')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_wallet_journal_au_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='UPDATE'
 AND BINARY ACTION_STATEMENT=BINARY 'BEGIN
 IF NEW.fund_revision<>OLD.fund_revision THEN
  INSERT INTO nx_support_wallet_journal (
   wallet_incarnation, revision, observation, old_id, new_id, old_user_id, new_user_id, old_usdt_available, new_usdt_available, old_nex_available, new_nex_available, old_pending_withdraw, new_pending_withdraw, old_cregis_risk_held, new_cregis_risk_held, old_version, new_version, old_sandbox, new_sandbox, old_is_deleted, new_is_deleted, old_created_at, new_created_at, recorded_db_utc, protocol_sha256
  ) VALUES (
   NEW.fund_incarnation, NEW.fund_revision, ''CHANGE'',
   OLD.id, NEW.id, OLD.user_id, NEW.user_id, OLD.usdt_available, NEW.usdt_available, OLD.nex_available, NEW.nex_available, OLD.pending_withdraw, NEW.pending_withdraw, OLD.cregis_risk_held, NEW.cregis_risk_held, OLD.version, NEW.version, OLD.sandbox, NEW.sandbox, OLD.is_deleted, NEW.is_deleted, OLD.created_at, NEW.created_at, UTC_TIMESTAMP(6), SHA2(''SUPPORT_WALLET_JOURNAL_V1'',256)
  );
 END IF;
END')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_user_wallet')=4
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_user_wallet' AND TRIGGER_NAME IN ('nx_wallet_earnings_debit_counter_v1',
 'nx_support_wallet_journal_ai_v1','nx_support_wallet_journal_bu_v1','nx_support_wallet_journal_au_v1'))=4)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_user_wallet' AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE')=2
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_wallet_earnings_debit_counter_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE'
 AND BINARY ACTION_STATEMENT=
 BINARY 'SET NEW.earnings_usdt_debited=OLD.earnings_usdt_debited+GREATEST(OLD.usdt_available-NEW.usdt_available,0),NEW.earnings_nex_debited=OLD.earnings_nex_debited+GREATEST(OLD.nex_available-NEW.nex_available,0)')=1
 AND (SELECT ACTION_ORDER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_wallet_journal_bu_v1')>
 (SELECT ACTION_ORDER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_wallet_earnings_debit_counter_v1'))
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_wallet_journal' AND CONSTRAINT_TYPE='CHECK')=2
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc
 JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA
 AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_support_wallet_journal' AND tc.ENFORCED='YES'
 AND ((tc.CONSTRAINT_NAME='ck_support_wallet_journal_revision'
 AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*`?revision`?[[:space:]()]*>=[[:space:]()]*0[[:space:]()]*$','c'))
 OR (tc.CONSTRAINT_NAME='ck_support_wallet_journal_observation'
 AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*`?observation`?[[:space:]()]*(?i:in)[[:space:]]*[(][[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''NEW_ZERO''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''NEW_UNPROVEN''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''CHANGE''[[:space:]]*[)][[:space:]()]*$','c'))))=2)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
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
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='DEFAULT_GENERATED' AND LOWER(REGEXP_REPLACE(COLUMN_DEFAULT,'[()[:space:]]',''))='utc_timestamp6')))=13)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND CONSTRAINT_TYPE='CHECK')=4
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_support_fund_operation' AND tc.ENFORCED='YES'
 AND ((tc.CONSTRAINT_NAME='ck_support_fund_operation_identity' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*(?i:trim)[[:space:]]*[(][[:space:]()]*`?op_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*[[:space:]]*[)][[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?business_type`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?phase`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?source_table`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?source_key`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:char_length)[[:space:]]*[(][[:space:]()]*`?protocol_version`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*$','c'))
 OR (tc.CONSTRAINT_NAME='ck_support_fund_operation_environment' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*`?source_environment`?[[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''PRODUCTION''[[:space:]()]*$','c'))
 OR (tc.CONSTRAINT_NAME='ck_support_fund_operation_json' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?ordered_events`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''ARRAY''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?source_snapshot`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''OBJECT''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_length)[[:space:]]*[(][[:space:]()]*`?source_snapshot`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_type)[[:space:]]*[(][[:space:]()]*`?source_proof`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*=[[:space:]()]*(?:_ascii|_utf8mb4)?[[:space:]]*''OBJECT''[[:space:]()]*[[:space:]]*(?i:and)[[:space:]]*[[:space:]()]*(?i:json_length)[[:space:]]*[(][[:space:]()]*`?source_proof`?[[:space:]()]*[[:space:]]*[)][[:space:]()]*>[[:space:]()]*0[[:space:]()]*$','c'))
 OR (tc.CONSTRAINT_NAME='ck_support_fund_operation_digest' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*(?i:regexp_like)[[:space:]]*[(][[:space:]()]*`?receipt_sha256`?[[:space:]()]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''[^'']*''[[:space:]]*,[[:space:]]*(?:_ascii|_utf8mb4)?[[:space:]]*''c''[[:space:]]*[)][[:space:]()]*$','c')
 AND BINARY REGEXP_SUBSTR(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'''[^'']*''')=BINARY '''^[0-9a-f]{64}$''')))=4)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation_leg')=3
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation_leg'
 AND ((COLUMN_NAME='operation_id' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='wallet_incarnation' AND COLUMN_TYPE='char(36)' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND COLLATION_NAME='ascii_bin' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)
 OR (COLUMN_NAME='revision' AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO'
  AND COALESCE(GENERATION_EXPRESSION,'')='' AND EXTRA='' AND COLUMN_DEFAULT IS NULL)))=3)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND CONSTRAINT_TYPE='CHECK')=1
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_support_fund_operation_leg' AND tc.ENFORCED='YES'
 AND ((tc.CONSTRAINT_NAME='ck_support_fund_operation_leg_revision' AND REGEXP_LIKE(REPLACE(cc.CHECK_CLAUSE,CONCAT(CHAR(92),CHAR(39)),CHAR(39)),'^[[:space:]()]*`?revision`?[[:space:]()]*>=[[:space:]()]*0[[:space:]()]*$','c'))))=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND NON_UNIQUE=0)=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND NON_UNIQUE=0 AND SUB_PART IS NULL AND IS_VISIBLE='YES'
 AND ((INDEX_NAME='PRIMARY' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id')
 OR (INDEX_NAME='uk_support_fund_operation_key' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='op_key')))=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND NON_UNIQUE=0)=2
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND INDEX_NAME='PRIMARY' AND SUB_PART IS NULL AND IS_VISIBLE='YES'
 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME='wallet_incarnation') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME='revision')))=2)
          AND
          (NOT EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation' AND REFERENCED_TABLE_NAME IS NOT NULL)
 AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_leg' AND REFERENCED_TABLE_NAME IS NOT NULL)=1
 AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r
 ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME
 WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME='nx_support_fund_operation_leg'
 AND k.CONSTRAINT_NAME='fk_support_fund_operation_leg_operation' AND k.COLUMN_NAME='operation_id'
 AND k.ORDINAL_POSITION=1 AND k.REFERENCED_TABLE_SCHEMA=DATABASE()
 AND k.REFERENCED_TABLE_NAME='nx_support_fund_operation' AND k.REFERENCED_COLUMN_NAME='id'
 AND r.UPDATE_RULE='RESTRICT' AND r.DELETE_RULE='RESTRICT')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_fund_operation_bu_v1' AND EVENT_OBJECT_TABLE='nx_support_fund_operation' AND ACTION_TIMING='BEFORE'
 AND EVENT_MANIPULATION='UPDATE' AND BINARY ACTION_STATEMENT=
 BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_fund_operation_bd_v1' AND EVENT_OBJECT_TABLE='nx_support_fund_operation' AND ACTION_TIMING='BEFORE'
 AND EVENT_MANIPULATION='DELETE' AND BINARY ACTION_STATEMENT=
 BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_fund_leg_bu_v1' AND EVENT_OBJECT_TABLE='nx_support_fund_operation_leg' AND ACTION_TIMING='BEFORE'
 AND EVENT_MANIPULATION='UPDATE' AND BINARY ACTION_STATEMENT=
 BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_support_fund_leg_bd_v1' AND EVENT_OBJECT_TABLE='nx_support_fund_operation_leg' AND ACTION_TIMING='BEFORE'
 AND EVENT_MANIPULATION='DELETE' AND BINARY ACTION_STATEMENT=
 BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE IN ('nx_support_fund_operation','nx_support_fund_operation_leg'))=4)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')=1
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation_party')=2
 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_fund_operation_party'
 AND COLUMN_NAME IN ('operation_id','customer_id') AND COLUMN_TYPE='bigint' AND IS_NULLABLE='NO'
 AND COLUMN_DEFAULT IS NULL AND EXTRA='' AND COALESCE(GENERATION_EXPRESSION,'')='')=2)
          AND
          ((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party')=3
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party' AND CONSTRAINT_NAME='PRIMARY' AND CONSTRAINT_TYPE='PRIMARY KEY')=1
 AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc
 ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_support_fund_operation_party'
 AND tc.CONSTRAINT_NAME='ck_support_fund_party_customer' AND tc.CONSTRAINT_TYPE='CHECK' AND tc.ENFORCED='YES'
 AND REGEXP_LIKE(cc.CHECK_CLAUSE,'^[[:space:]()]*`?customer_id`?[[:space:]()]*>[[:space:]()]*0[[:space:]()]*$','c'))=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party')=4
 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_support_fund_operation_party' AND INDEX_TYPE='BTREE' AND IS_VISIBLE='YES' AND SUB_PART IS NULL
 AND EXPRESSION IS NULL AND COLLATION='A'
 AND ((INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME='operation_id') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME='customer_id')))
 OR (INDEX_NAME='idx_support_fund_party_customer' AND NON_UNIQUE=1 AND ((SEQ_IN_INDEX=1 AND COLUMN_NAME='customer_id') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME='operation_id')))))=4)
          AND
          ((SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
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
 AND r.MATCH_OPTION='NONE' AND r.UPDATE_RULE='RESTRICT' AND r.DELETE_RULE='RESTRICT')=1)
          AND
          ((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=2
 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party' AND EVENT_OBJECT_SCHEMA=DATABASE()
 AND ACTION_TIMING='BEFORE' AND ACTION_ORIENTATION='ROW' AND ACTION_ORDER=1
 AND ((TRIGGER_NAME='nx_support_fund_party_bu_v1' AND EVENT_MANIPULATION='UPDATE')
 OR (TRIGGER_NAME='nx_support_fund_party_bd_v1' AND EVENT_MANIPULATION='DELETE'))
 AND BINARY ACTION_STATEMENT=BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=2)
        )
);
-- FUNCTION_BODY_END

SET @fund_schema_reader_installed_ok=((SELECT COUNT(*) FROM information_schema.ROUTINES
 WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='nx_support_fund_schema_ready_v1'
 AND ROUTINE_TYPE='FUNCTION' AND DATA_TYPE='tinyint' AND DTD_IDENTIFIER='tinyint unsigned'
 AND IS_DETERMINISTIC='NO' AND SQL_DATA_ACCESS='READS SQL DATA'
 AND SECURITY_TYPE='DEFINER' AND DEFINER=CURRENT_USER()
 AND BINARY ROUTINE_DEFINITION=BINARY @fund_schema_reader_expected_body)=1
 AND NOT EXISTS(SELECT 1 FROM information_schema.PARAMETERS
 WHERE SPECIFIC_SCHEMA=DATABASE() AND SPECIFIC_NAME='nx_support_fund_schema_ready_v1' AND ORDINAL_POSITION>0));
SET @fund_schema_reader_sql=IF(@fund_schema_reader_installed_ok=1,'SELECT 1','FAIL SUPPORT_FUND_SCHEMA_READER_INSTALLED_SHAPE');
PREPARE fund_schema_reader_stmt FROM @fund_schema_reader_sql; EXECUTE fund_schema_reader_stmt; DEALLOCATE PREPARE fund_schema_reader_stmt;
SET @fund_schema_reader_ready=nx_support_fund_schema_ready_v1();
SET @fund_schema_reader_sql=IF(@fund_schema_reader_ready=1,'SELECT 1','FAIL SUPPORT_FUND_SCHEMA_READER_NOT_READY');
PREPARE fund_schema_reader_stmt FROM @fund_schema_reader_sql; EXECUTE fund_schema_reader_stmt; DEALLOCATE PREPARE fund_schema_reader_stmt;
SELECT RELEASE_LOCK(@fund_schema_reader_lock);
