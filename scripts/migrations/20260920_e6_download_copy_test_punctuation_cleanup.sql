-- E6 客户端下载英文说明的测试标点清理。
--
-- Why: `E.compute.download.enGuide` 的存量值以「！！！Download the desktop client...」
-- 开头。E6 页面已识别它含测试标点、也不会把它当正式文案下发,但**库里的值本身**
-- 一直是脏的:每次打开都显示这串未审内容,且任何按前缀读取该键的投影都会拿到它
-- (zentao #45)。门禁只拦新写入,拦不住存量。
--
-- 只修这一条已知脏值,并且只在它仍以测试标点开头时改写 —— 运营若已改成正式文案,
-- 本迁移不动它。写入的文案与 ComputeConfigRegistry.DOWNLOAD_FIELDS 的英文默认值
-- 同源(去掉测试标点后的正式表述)。
--
-- 幂等:第二次启动没有行命中谓词。

UPDATE nx_config_item
   SET config_value = 'Download the desktop client, sign in with the same account, and the computer appears in device inventory after connection.',
       updated_at = NOW()
 WHERE config_key = 'E.compute.download.enGuide'
   AND config_value REGEXP '^[[:space:]]*[!?！？]{2,}';

-- Postcondition: no registered download copy may still lead with test punctuation.
-- A bare SELECT would only print a verdict the controlled runner does not parse, so
-- the check raises SQLSTATE 45000 and stops backend startup instead of presenting a
-- half-applied cleanup as success.
SET @compute_copy_clean = (
  SELECT COUNT(*) = 0
    FROM nx_config_item
   WHERE config_key LIKE 'E.compute.download.%'
     AND config_value REGEXP '[!?！？]{2,}'
);
SET @sql = IF(
  @compute_copy_clean,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''COMPUTE_DOWNLOAD_COPY_TEST_PUNCTUATION_PRESENT'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
