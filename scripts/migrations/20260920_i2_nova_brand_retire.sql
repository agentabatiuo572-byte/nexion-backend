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
--
-- 🔴 替换必须**大小写不敏感**,否则本迁移会自己把后端启动打挂:谓词用 REGEXP,
-- 而 MySQL 8 的 REGEXP 跟随列排序规则,默认 *_ci 下 `[Nn]exion` 连 `NEXION` 都命中;
-- `REPLACE()` 却大小写敏感,只认字面量 'Nexion'。于是「谓词命中 → 改不动 →
-- 后置断言失败 → SIGNAL 45000 → 启动中止」。用 REGEXP_REPLACE(...,'i') 让
-- 「改得动的集合」= 「断言要求的集合」。
UPDATE nx_nova_template
   SET title_zh = REGEXP_REPLACE(title_zh, '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i'),
       body_zh  = REGEXP_REPLACE(body_zh,  '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i'),
       title_vi = REGEXP_REPLACE(title_vi, '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i'),
       body_vi  = REGEXP_REPLACE(body_vi,  '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i'),
       title_en = REGEXP_REPLACE(title_en, '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i'),
       body_en  = REGEXP_REPLACE(body_en,  '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i'),
       updated_at = NOW(),
       reason = CONCAT(COALESCE(reason, ''), ' | retire Nexion brand')
 WHERE is_deleted = 0
   AND (title_zh REGEXP '(^|[^[:alnum:]])[Nn]exion'
     OR body_zh  REGEXP '(^|[^[:alnum:]])[Nn]exion'
     OR title_vi REGEXP '(^|[^[:alnum:]])[Nn]exion'
     OR body_vi  REGEXP '(^|[^[:alnum:]])[Nn]exion'
     OR title_en REGEXP '(^|[^[:alnum:]])[Nn]exion'
     OR body_en  REGEXP '(^|[^[:alnum:]])[Nn]exion');

-- Postcondition: no live template may still carry the retired brand. A bare
-- SELECT would only print a verdict the controlled runner does not parse, so the
-- check raises SQLSTATE 45000: mysql exits non-zero and backend startup stops
-- rather than presenting a half-applied rebrand as success.
SET @nova_brand_retired = (
  SELECT COUNT(*) = 0
    FROM nx_nova_template
   WHERE is_deleted = 0
     AND (title_zh REGEXP '(^|[^[:alnum:]])[Nn]exion'
       OR body_zh  REGEXP '(^|[^[:alnum:]])[Nn]exion'
       OR title_vi REGEXP '(^|[^[:alnum:]])[Nn]exion'
       OR body_vi  REGEXP '(^|[^[:alnum:]])[Nn]exion'
       OR title_en REGEXP '(^|[^[:alnum:]])[Nn]exion'
       OR body_en  REGEXP '(^|[^[:alnum:]])[Nn]exion')
);
SET @sql = IF(
  @nova_brand_retired,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''NOVA_TEMPLATE_BRAND_RETIRE_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
