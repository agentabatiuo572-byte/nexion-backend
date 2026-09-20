-- H4 活动 evt-spring-spin 的三语文案补齐。
--
-- Why: `nx_event_quest` 只存了运营原始英文文案(quest_name='Daily Lucky Spin',
-- description='One free spin per UTC day plus H5 bonus tickets')。App 的展示文案来自
-- `growth.content.localized` 的 `$.event.<code>.<locale>.{name,description,rewardName}`,
-- zh/vi 缺配置时 AppGrowthEngagementMapper 静默回退事件原文,于是中文 App 整段显示英文
-- (zentao #194)。
--
-- 发布门(updateEventStatus 的 EVENT_LOCALIZED_CONTENT_INCOMPLETE)只拦**新发布**;
-- 这个活动已经是 ongoing,门拦不住存量,所以必须在这里补数据。
--
-- 只补**缺失**的字段:已存在的运营填写值一律保留(JSON_SET 只在路径不存在时写入由
-- JSON_EXTRACT 判空控制)。幂等:第二次启动两个谓词都不再命中。
--
-- 与 Java 侧 REQUIRED_EVENT_LOCALES(zh/vi)+ REQUIRED_EVENT_FIELDS(name/description/
-- rewardName)同一口径 —— 这里补的正是那份清单上的六项。

SET @h4_localized_key = 'growth.content.localized';

-- 1. 建立 event 分支与 evt-spring-spin 节点(不存在时)。
UPDATE nx_config_item
   SET config_value = JSON_SET(
         CASE WHEN JSON_VALID(config_value) THEN config_value ELSE '{}' END,
         '$.event."evt-spring-spin"',
         COALESCE(JSON_EXTRACT(CASE WHEN JSON_VALID(config_value) THEN config_value ELSE '{}' END,
                               '$.event."evt-spring-spin"'), JSON_OBJECT())),
       updated_at = NOW()
 WHERE config_key = @h4_localized_key AND is_deleted = 0;

-- 2. 逐字段只在缺失时写入。
UPDATE nx_config_item
   SET config_value = JSON_SET(
         config_value,
         '$.event."evt-spring-spin".zh.name',
         COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".zh.name')), ''), '幸运轮盘'),
         '$.event."evt-spring-spin".zh.description',
         COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".zh.description')), ''), '每个 UTC 日可免费抽一次，另有 H5 奖励券加成'),
         '$.event."evt-spring-spin".zh.rewardName',
         COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".zh.rewardName')), ''), '轮盘奖池奖励'),
         '$.event."evt-spring-spin".vi.name',
         COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".vi.name')), ''), 'Vòng quay may mắn'),
         '$.event."evt-spring-spin".vi.description',
         COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".vi.description')), ''), 'Mỗi ngày UTC được quay miễn phí một lần, kèm vé thưởng H5'),
         '$.event."evt-spring-spin".vi.rewardName',
         COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".vi.rewardName')), ''), 'Phần thưởng từ quỹ vòng quay')),
       updated_at = NOW()
 WHERE config_key = @h4_localized_key AND is_deleted = 0;

-- Postcondition: the six required fields must be non-empty. A bare SELECT would only
-- print a verdict the controlled runner does not parse, so the check raises SQLSTATE
-- 45000 and stops backend startup instead of presenting a half-applied provisioning
-- as success.
SET @h4_event_content_ready = (
  SELECT COUNT(*) = 6
    FROM (
      SELECT JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".zh.name'))        AS v FROM nx_config_item WHERE config_key = @h4_localized_key AND is_deleted = 0
      UNION ALL
      SELECT JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".zh.description')) AS v FROM nx_config_item WHERE config_key = @h4_localized_key AND is_deleted = 0
      UNION ALL
      SELECT JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".zh.rewardName'))  AS v FROM nx_config_item WHERE config_key = @h4_localized_key AND is_deleted = 0
      UNION ALL
      SELECT JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".vi.name'))        AS v FROM nx_config_item WHERE config_key = @h4_localized_key AND is_deleted = 0
      UNION ALL
      SELECT JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".vi.description')) AS v FROM nx_config_item WHERE config_key = @h4_localized_key AND is_deleted = 0
      UNION ALL
      SELECT JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.event."evt-spring-spin".vi.rewardName'))  AS v FROM nx_config_item WHERE config_key = @h4_localized_key AND is_deleted = 0
    ) fields
   WHERE v IS NOT NULL AND v <> ''
);
SET @sql = IF(
  @h4_event_content_ready,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''H4_EVENT_LOCALIZED_CONTENT_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
