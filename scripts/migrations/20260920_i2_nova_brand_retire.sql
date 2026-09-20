-- I2 Nova 推送模板退役品牌纠偏。
--
-- Why: 三条已发布模板 (social / welcome / wrapped) 的三语正文仍写「Nexion」,
-- 而当前用户品牌是 NexGrid。Nova 会把已发布模板正文原样推送给用户,所以这些
-- 旧品牌会继续出现在真实推送里 (zentao #147)。
--
-- 只改三语标题/正文里出现旧品牌的模板行,按整词匹配 (避免误伤含 nexion 字母
-- 序列的其它词),不碰 CAP/渠道/分发配置,也不改变发布状态 —— 文案修好即应继续
-- 处于已发布。幂等:第二次启动没有行命中谓词。
--
-- 与 Java 侧 `RetiredBrandGate.RETIRED_BRAND_REGEX` 同一口径:新发布由代码门禁
-- 拦住,存量由本迁移修好,两边不会再分叉。

UPDATE nx_nova_template
   SET title_zh = REPLACE(title_zh, 'Nexion', 'NexGrid'),
       body_zh  = REPLACE(body_zh,  'Nexion', 'NexGrid'),
       title_vi = REPLACE(title_vi, 'Nexion', 'NexGrid'),
       body_vi  = REPLACE(body_vi,  'Nexion', 'NexGrid'),
       title_en = REPLACE(title_en, 'Nexion', 'NexGrid'),
       body_en  = REPLACE(body_en,  'Nexion', 'NexGrid'),
       updated_at = NOW(),
       reason = CONCAT(COALESCE(reason, ''), ' | retire Nexion brand')
 WHERE is_deleted = 0
   AND (title_zh REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
     OR body_zh  REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
     OR title_vi REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
     OR body_vi  REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
     OR title_en REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
     OR body_en  REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)');

-- Postcondition: no live template may still carry the retired brand. A bare
-- SELECT would only print a verdict the controlled runner does not parse, so the
-- check raises SQLSTATE 45000: mysql exits non-zero and backend startup stops
-- rather than presenting a half-applied rebrand as success.
SET @nova_brand_retired = (
  SELECT COUNT(*) = 0
    FROM nx_nova_template
   WHERE is_deleted = 0
     AND (title_zh REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
       OR body_zh  REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
       OR title_vi REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
       OR body_vi  REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
       OR title_en REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
       OR body_en  REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)')
);
SET @sql = IF(
  @nova_brand_retired,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''NOVA_TEMPLATE_BRAND_RETIRE_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
