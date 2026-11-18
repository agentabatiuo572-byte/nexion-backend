-- I2-BE-06 / I2-BE-09(1): retire the last legacy brand tokens and internal page
-- codes still present in Nova preloaded templates and channel trigger copy.
--
-- Why: the I2 canonical seed (scripts/migrations/20260722_i2_nova_closure.sql)
-- wrote the market body with the retired token NEX and five channel trigger_rule
-- rows with internal page codes (E3/H3/H4) or NEX. Earlier migrations retired
-- Nexion/NexGrid titles and moved DDL/seed out of the read path, but this visible
-- copy remained.
--
-- Only canonical stock rows are rewritten; operator-edited copy is preserved.
-- Updates are idempotent: a second startup has no matching rows. A postcondition
-- raises SQLSTATE 45000 so backend startup stops rather than presenting a
-- half-applied cleanup as success.

UPDATE nx_nova_template
   SET body_zh = REGEXP_REPLACE(body_zh, '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)', '$1UVT$2', 1, 0, 'i'),
       body_vi = REGEXP_REPLACE(body_vi, '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)', '$1UVT$2', 1, 0, 'i'),
       body_en = REGEXP_REPLACE(body_en, '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)', '$1UVT$2', 1, 0, 'i'),
       title_zh = REGEXP_REPLACE(title_zh, '(^|[^[:alnum:]])NexGrid([^[:alnum:]]|$)', '$1UVEL$2', 1, 0, 'i'),
       title_vi = REGEXP_REPLACE(title_vi, '(^|[^[:alnum:]])NexGrid([^[:alnum:]]|$)', '$1UVEL$2', 1, 0, 'i'),
       title_en = REGEXP_REPLACE(title_en, '(^|[^[:alnum:]])NexGrid([^[:alnum:]]|$)', '$1UVEL$2', 1, 0, 'i'),
       body_zh = REGEXP_REPLACE(body_zh, '(^|[^[:alnum:]])NexGrid([^[:alnum:]]|$)', '$1UVEL$2', 1, 0, 'i'),
       body_vi = REGEXP_REPLACE(body_vi, '(^|[^[:alnum:]])NexGrid([^[:alnum:]]|$)', '$1UVEL$2', 1, 0, 'i'),
       body_en = REGEXP_REPLACE(body_en, '(^|[^[:alnum:]])NexGrid([^[:alnum:]]|$)', '$1UVEL$2', 1, 0, 'i'),
       updated_at = NOW(),
       reason = CONCAT(COALESCE(reason, ''), ' | I2 retire legacy brand copy')
 WHERE is_deleted = 0
   AND (body_zh REGEXP '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)'
     OR body_vi REGEXP '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)'
     OR body_en REGEXP '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)'
     OR title_zh REGEXP '(^|[^[:alnum:]])[Nn]exGrid([^[:alnum:]]|$)'
     OR title_vi REGEXP '(^|[^[:alnum:]])[Nn]exGrid([^[:alnum:]]|$)'
     OR title_en REGEXP '(^|[^[:alnum:]])[Nn]exGrid([^[:alnum:]]|$)'
     OR body_zh REGEXP '(^|[^[:alnum:]])[Nn]exGrid([^[:alnum:]]|$)'
     OR body_vi REGEXP '(^|[^[:alnum:]])[Nn]exGrid([^[:alnum:]]|$)'
     OR body_en REGEXP '(^|[^[:alnum:]])[Nn]exGrid([^[:alnum:]]|$)');

UPDATE nx_nova_channel
   SET trigger_rule = CASE
       WHEN BINARY trigger_rule = '全网算力或 NEX 行情满足播报条件' THEN '全网算力或 UVT 行情满足播报条件'
       WHEN BINARY trigger_rule = '符合 E3 以旧换新资格时触达' THEN '符合以旧换新资格时触达'
       WHEN BINARY trigger_rule = 'H4 活动奖励进入可领取态时触达' THEN '活动奖励进入可领取态时触达'
       WHEN BINARY trigger_rule = 'H3 月度任务锁定累计条件满足时触达' THEN '月度任务锁定累计条件满足时触达'
       WHEN BINARY trigger_rule = 'H3 任务状态进入宽限、过期或周刷新时触达' THEN '任务状态进入宽限、过期或周刷新时触达'
       ELSE trigger_rule
     END,
       updated_at = NOW(),
       reason = CONCAT(COALESCE(reason, ''), ' | I2 trigger copy de-paged/branded')
 WHERE is_deleted = 0
   AND channel_key IN ('market', 'tradein', 'eventClaim', 'taskLockMonthly', 'quest')
   AND BINARY trigger_rule IN (
       '全网算力或 NEX 行情满足播报条件',
       '符合 E3 以旧换新资格时触达',
       'H4 活动奖励进入可领取态时触达',
       'H3 月度任务锁定累计条件满足时触达',
       'H3 任务状态进入宽限、过期或周刷新时触达');

SET @i2_nova_copy_clean = (
  SELECT (COUNT(*) = 0)
    FROM (
      SELECT id FROM nx_nova_template
       WHERE is_deleted = 0
         AND (body_zh REGEXP '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)'
           OR body_vi REGEXP '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)'
           OR body_en REGEXP '(^|[^[:alnum:]])NEX([^[:alnum:]]|$)')
      UNION ALL
      SELECT id FROM nx_nova_channel
       WHERE is_deleted = 0
         AND channel_key IN ('market', 'tradein', 'eventClaim', 'taskLockMonthly', 'quest')
         AND BINARY trigger_rule IN (
             '全网算力或 NEX 行情满足播报条件',
             '符合 E3 以旧换新资格时触达',
             'H4 活动奖励进入可领取态时触达',
             'H3 月度任务锁定累计条件满足时触达',
             'H3 任务状态进入宽限、过期或周刷新时触达')
    ) legacy_copy
);
SET @sql = IF(
  @i2_nova_copy_clean,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''NOVA_LEGACY_BRAND_COPY_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
