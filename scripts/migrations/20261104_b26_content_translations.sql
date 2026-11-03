-- Optional operator-authored translations. Existing versions retain their English fallback.
SET @b26_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_disclosure_draft' AND column_name='translations_json'),
  'SELECT 1', 'ALTER TABLE nx_disclosure_draft ADD COLUMN translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl;
EXECUTE b26_stmt;
DEALLOCATE PREPARE b26_stmt;
SET @b26_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_disclosure_chapter' AND column_name='title_translations_json'),
  'SELECT 1', 'ALTER TABLE nx_disclosure_chapter ADD COLUMN title_translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl;
EXECUTE b26_stmt;
DEALLOCATE PREPARE b26_stmt;
SET @b26_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_disclosure_chapter' AND column_name='body_translations_json'),
  'SELECT 1', 'ALTER TABLE nx_disclosure_chapter ADD COLUMN body_translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl;
EXECUTE b26_stmt;
DEALLOCATE PREPARE b26_stmt;
CREATE TABLE IF NOT EXISTS nx_genesis_raffle_translation (
  draw_id VARCHAR(64) NOT NULL PRIMARY KEY,
  title_translations_json JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- A draft retains its published JSON: the existing six-page publication alone exceeds 32 KiB.
-- Preserve the column charset, collation, nullability, default and comment when widening a TEXT.
SET @b26_ddl = (SELECT IF(DATA_TYPE IN ('tinytext','text'), CONCAT(
  'ALTER TABLE nx_config_item MODIFY COLUMN config_value MEDIUMTEXT CHARACTER SET ', CHARACTER_SET_NAME,
  ' COLLATE ', COLLATION_NAME, IF(IS_NULLABLE='YES',' NULL',' NOT NULL'),
  IF(COLUMN_DEFAULT IS NULL,'',CONCAT(' DEFAULT (',QUOTE(COLUMN_DEFAULT),')')),
  ' COMMENT ',QUOTE(COLUMN_COMMENT)), 'SELECT 1') FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_config_item' AND column_name='config_value');
PREPARE b26_stmt FROM @b26_ddl;
EXECUTE b26_stmt;
DEALLOCATE PREPARE b26_stmt;
-- New Day One instances freeze currently authored names. Earlier instances have no translation snapshot.
SET @b26_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_growth_day_one_instance_item' AND column_name='name_translations_json'),
  'SELECT 1', 'ALTER TABLE nx_growth_day_one_instance_item ADD COLUMN name_translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl;
EXECUTE b26_stmt;
DEALLOCATE PREPARE b26_stmt;
-- Each optional translation belongs to the same FAQ id, publication state and revision as its English source.
SET @b26_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_help_article' AND column_name='faq_translations_json'),
  'SELECT 1', 'ALTER TABLE nx_help_article ADD COLUMN faq_translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl;
EXECUTE b26_stmt;
DEALLOCATE PREPARE b26_stmt;
