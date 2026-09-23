-- I6 已发布三语词条的**存量**占位文本清理(zentao #67)。
--
-- 扫描侧的内容判据已经在 `MybatisI18nLearningRepository.recomputeIntegrity` 里补齐
-- (placeholder-text / retired-brand 两类),它会把「已发布但正文是 ccccc」这类词条报出来。
-- 但**报出来不等于修好**:那些行仍在库里,运营每次打开 I6 都看到同一批未处理项,
-- 而页面按当前语言取到的仍是占位正文。这一半此前完全缺失 —— 本次补上。
--
-- 判据与扫描侧严格同口径(`isPlaceholderText`),只处理**确定是占位**的行:
--   · 去掉空白后整串由同一种字符重复至少五次(ccccc / xxxxx / -----);
--   · 去掉标点后整串只由 test|todo|tbd|placeholder|dummy|样例|测试|占位 组成。
-- 命中即把该行退回草稿(status=0),**不发明替代文案**:占位文本没有权威来源,
-- 由运营在 I6 按语言重写后重新发布。status 语义见 I18nMessageEntity(1=已发布)。
--
-- 幂等:只改 status=1 且命中判据的行;重复执行时已改过的行不再命中。

UPDATE nx_i18n_message
   SET status = 0,
       updated_at = NOW()
 WHERE status = 1
   AND is_deleted = 0
   AND message_value IS NOT NULL
   AND (
     REGEXP_REPLACE(message_value, '[[:space:]]+', '') REGEXP '^(.)\\1{4,}$'
     OR LOWER(REGEXP_REPLACE(REGEXP_REPLACE(message_value, '[[:space:]]+', ''), '[[:punct:]]+', ''))
        REGEXP '^(test|todo|tbd|placeholder|dummy|样例|测试|占位)+$'
   );

-- Postcondition: no published row may still match the placeholder criteria. A bare
-- SELECT would only print a verdict the controlled runner does not parse, so the
-- check raises SQLSTATE 45000: mysql exits non-zero and backend startup stops
-- rather than presenting a half-applied cleanup as success.
SET @i6_placeholder_clean = (
  SELECT COUNT(*) = 0
    FROM nx_i18n_message
   WHERE status = 1
     AND is_deleted = 0
     AND message_value IS NOT NULL
     AND (
       REGEXP_REPLACE(message_value, '[[:space:]]+', '') REGEXP '^(.)\\1{4,}$'
       OR LOWER(REGEXP_REPLACE(REGEXP_REPLACE(message_value, '[[:space:]]+', ''), '[[:punct:]]+', ''))
          REGEXP '^(test|todo|tbd|placeholder|dummy|样例|测试|占位)+$'
     )
);
SET @sql = IF(
  @i6_placeholder_clean,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''I6_PLACEHOLDER_TEXT_CLEANUP_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
