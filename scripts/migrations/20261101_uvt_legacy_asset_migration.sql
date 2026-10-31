-- Code-only asset rename. Amounts, wallet balances, business identifiers and raw
-- audit/event JSON remain unchanged. Stop writers and retain the normal backup.
-- These widened checks permit an atomic legacy upgrade; application writes UVT only.
-- Replaces startup replay of 20261025_promotion_order_discount.sql (NEX-only checks).
ALTER TABLE nx_promotion_budget
 DROP CHECK chk_promotion_budget_kind,
 ADD CONSTRAINT chk_promotion_budget_kind CHECK (
   (asset IN ('USDT','NEX','UVT','DISCOUNT_USDT') AND product_no='')
   OR (asset='DEVICE' AND product_no<>''));
ALTER TABLE nx_promotion_reservation
 DROP CHECK chk_promotion_reservation_asset,
 DROP CHECK chk_promotion_reservation_state,
 ADD CONSTRAINT chk_promotion_reservation_asset CHECK (
   (asset IN ('USDT','NEX','UVT','DISCOUNT_USDT') AND product_no='')
   OR (asset='DEVICE' AND product_no<>'' AND amount=FLOOR(amount))),
 ADD CONSTRAINT chk_promotion_reservation_state CHECK (
   status IN ('RESERVED','COMMITTED','RELEASED')
   OR (asset='DISCOUNT_USDT' AND status='REVERSED'));
ALTER TABLE nx_promotion_reversal
 DROP CHECK chk_promotion_reversal_asset,
 ADD CONSTRAINT chk_promotion_reversal_asset CHECK (asset IN ('DEVICE','USDT','NEX','UVT'));
ALTER TABLE nx_earnings_source_recovery
 DROP CHECK chk_earnings_recovery_asset,
 ADD CONSTRAINT chk_earnings_recovery_asset CHECK (asset IN ('USDT','NEX','UVT'));

-- Market samples are not account postings. If an old unique index would collide,
-- keep the operator's existing UVT quote and preserve the FULL old sample here.
-- Equal and unequal quotes follow the same policy; no newer quote is overwritten.
CREATE TABLE IF NOT EXISTS nx_uvt_legacy_price_archive (
 original_id BIGINT PRIMARY KEY,
 target_id BIGINT NOT NULL,
 target_metric_code VARCHAR(96) NOT NULL,
 conflict_index VARCHAR(64) NOT NULL,
 original_row JSON NOT NULL,
 archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS migrate_legacy_uvt_assets_20261101;
DELIMITER $$
CREATE PROCEDURE migrate_legacy_uvt_assets_20261101()
BEGIN
 DECLARE finished INT DEFAULT 0;
 DECLARE table_name_value VARCHAR(64);
 DECLARE column_name_value VARCHAR(64);
 DECLARE index_name_value VARCHAR(64);
 DECLARE predicate_value LONGTEXT;
 DECLARE price_json_value LONGTEXT;
 DECLARE error_value TEXT;
 -- Inventory is schema-driven, limited to structured currency/code fields.
 -- No JSON, prose, column names (*_nex), source refs or hashes are rewritten.
 DECLARE asset_columns CURSOR FOR
  SELECT c.TABLE_NAME,c.COLUMN_NAME FROM information_schema.COLUMNS c
  JOIN information_schema.TABLES t ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME
  WHERE c.TABLE_SCHEMA=DATABASE() AND LEFT(c.TABLE_NAME,3)='nx_' AND t.TABLE_TYPE='BASE TABLE'
    AND c.COLUMN_NAME IN ('asset','currency','from_asset','to_asset','payment_asset','reward_type','rank_currency')
    AND c.DATA_TYPE IN ('varchar','char') ORDER BY c.TABLE_NAME,c.ORDINAL_POSITION;
 DECLARE price_indexes CURSOR FOR
  SELECT INDEX_NAME FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_price_index' AND NON_UNIQUE=0
    AND COLUMN_NAME='metric_code' ORDER BY INDEX_NAME;
 DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=1;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION
 BEGIN
  GET DIAGNOSTICS CONDITION 1 error_value=MESSAGE_TEXT;
  ROLLBACK;
  SET error_value=LEFT(CONCAT('UVT_LEGACY_MIGRATION_CONFLICT: ',error_value),128);
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=error_value;
 END;
 SET SESSION group_concat_max_len=1048576;
 START TRANSACTION;
 IF EXISTS (SELECT 1 FROM information_schema.TABLES t JOIN information_schema.COLUMNS c
     ON c.TABLE_SCHEMA=t.TABLE_SCHEMA AND c.TABLE_NAME=t.TABLE_NAME
     WHERE t.TABLE_SCHEMA=DATABASE() AND LEFT(t.TABLE_NAME,3)='nx_' AND t.TABLE_TYPE='BASE TABLE'
       AND c.COLUMN_NAME IN ('asset','currency','from_asset','to_asset','payment_asset','reward_type','rank_currency','metric_code')
       AND t.ENGINE<>'InnoDB') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='UVT_LEGACY_NON_TRANSACTIONAL_TABLE';
 END IF;
 SELECT GROUP_CONCAT(CONCAT(QUOTE(COLUMN_NAME),',a.`',REPLACE(COLUMN_NAME,'`','``'),'`') ORDER BY ORDINAL_POSITION SEPARATOR ',')
 INTO price_json_value FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_price_index';
 OPEN price_indexes;
 price_loop: LOOP
  FETCH price_indexes INTO index_name_value;
  IF finished=1 THEN LEAVE price_loop; END IF;
  IF EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME='nx_price_index' AND INDEX_NAME=index_name_value AND COLUMN_NAME IS NULL) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='UVT_PRICE_FUNCTIONAL_UNIQUE_REQUIRES_REVIEW';
  END IF;
  SELECT GROUP_CONCAT(
    CASE WHEN COLUMN_NAME='metric_code' THEN
      CONCAT(IF(SUB_PART IS NULL,'b.`metric_code`',CONCAT('LEFT(b.`metric_code`,',SUB_PART,')')),'=',
        IF(SUB_PART IS NULL,'CASE UPPER(a.metric_code) WHEN ''NEX'' THEN ''UVT'' ELSE ''UVT_USDT'' END',
          CONCAT('LEFT(CASE UPPER(a.metric_code) WHEN ''NEX'' THEN ''UVT'' ELSE ''UVT_USDT'' END,',SUB_PART,')')))
    ELSE CONCAT(IF(SUB_PART IS NULL,CONCAT('a.`',COLUMN_NAME,'`'),CONCAT('LEFT(a.`',COLUMN_NAME,'`,',SUB_PART,')')),'=',
       IF(SUB_PART IS NULL,CONCAT('b.`',COLUMN_NAME,'`'),CONCAT('LEFT(b.`',COLUMN_NAME,'`,',SUB_PART,')'))) END
    ORDER BY SEQ_IN_INDEX SEPARATOR ' AND ')
  INTO predicate_value FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_price_index' AND INDEX_NAME=index_name_value;
  SET @uvt_sql=CONCAT('INSERT INTO nx_uvt_legacy_price_archive(original_id,target_id,target_metric_code,conflict_index,original_row) ',
    'SELECT a.id,b.id,b.metric_code,',QUOTE(index_name_value),',JSON_OBJECT(',price_json_value,') ',
    'FROM nx_price_index a JOIN nx_price_index b ON ',predicate_value,
    ' WHERE UPPER(a.metric_code) IN (''NEX'',''NEX_USDT'') AND UPPER(b.metric_code) IN (''UVT'',''UVT_USDT'')');
  PREPARE uvt_stmt FROM @uvt_sql; EXECUTE uvt_stmt; DEALLOCATE PREPARE uvt_stmt;
  SET @uvt_sql=CONCAT('DELETE a FROM nx_price_index a JOIN nx_uvt_legacy_price_archive h ON h.original_id=a.id ',
    'WHERE UPPER(a.metric_code) IN (''NEX'',''NEX_USDT'')');
  PREPARE uvt_stmt FROM @uvt_sql; EXECUTE uvt_stmt; DEALLOCATE PREPARE uvt_stmt;
 END LOOP;
 CLOSE price_indexes;
 UPDATE nx_price_index SET metric_code=CASE UPPER(metric_code) WHEN 'NEX' THEN 'UVT' ELSE 'UVT_USDT' END,
   metric_label=REGEXP_REPLACE(metric_label,'NEX','UVT',1,0,'i'),
   unit_label=REGEXP_REPLACE(unit_label,'NEX','UVT',1,0,'i'),updated_at=updated_at
 WHERE UPPER(metric_code) IN ('NEX','NEX_USDT');
 SET finished=0;
 OPEN asset_columns;
 asset_loop: LOOP
  FETCH asset_columns INTO table_name_value,column_name_value;
  IF finished=1 THEN LEAVE asset_loop; END IF;
  -- Preserve updated_at as well as every monetary field and business key.
  SET @uvt_sql=CONCAT('UPDATE `',table_name_value,'` SET `',column_name_value,'`=',
    IF(column_name_value='reward_type','''uvt''','''UVT'''),
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
       AND TABLE_NAME=table_name_value AND COLUMN_NAME='updated_at'),',updated_at=updated_at',''),
    ' WHERE UPPER(`',column_name_value,'`)=''NEX''');
  PREPARE uvt_stmt FROM @uvt_sql; EXECUTE uvt_stmt; DEALLOCATE PREPARE uvt_stmt;
 END LOOP;
 CLOSE asset_columns;
 COMMIT;
END$$
DELIMITER ;
CALL migrate_legacy_uvt_assets_20261101();
DROP PROCEDURE migrate_legacy_uvt_assets_20261101;
