-- H2/E2 商品与任务门槛的退役品牌存量清理(zentao #73,并收口 #39 的旧品牌残留)。
--
-- 现状:`nx_product.name` 的旧品牌此前只被一条早期迁移覆盖了一行
-- (20260826_h2_trial_product_catalog.sql 只改 stellarbox-s1),其余行、以及
-- `nx_admin_device_task.requirement` 里的「需 NexionBox Pro」「需 NexionRack」
-- 仍是旧品牌。后果有两面:
--   · App 商城与 PC E2 任务列表继续显示旧品牌(NexionBox Pro v2 等);
--   · 后端写入白名单已改成新品牌
--     (OpsDeviceService.TASK_REQUIREMENTS = 需 NexGridBox Pro / 需 NexGridRack),
--     运营在 E2 抽屉里保存这些行会因旧值不在白名单而报 TASK_REQUIREMENT_INVALID
--     —— 即「显示旧值 + 存不回去」的死结。
--
-- 按整词匹配改(避免误伤含 nexion 字母序列的其它词),与
-- RetiredBrandGate.RETIRED_BRAND_REGEX 同一口径;新写入由代码门禁拦住,存量由本迁移修好。
-- 幂等:第二次启动没有行命中谓词。

UPDATE nx_product
   SET name = REPLACE(name, 'Nexion', 'NexGrid'),
       updated_at = NOW()
 WHERE is_deleted = 0
   AND name REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)';

-- E2 任务门槛文案与后端白名单逐字对齐(不是简单替换:白名单是固定枚举)。
UPDATE nx_admin_device_task
   SET requirement = CASE requirement
         WHEN '需 NexionBox Pro' THEN '需 NexGridBox Pro'
         WHEN '需 NexionRack'    THEN '需 NexGridRack'
         ELSE requirement
       END,
       updated_at = NOW()
 WHERE is_deleted = 0
   AND requirement IN ('需 NexionBox Pro', '需 NexionRack');

-- Postcondition: no live row may still carry the retired brand. A bare SELECT
-- would only print a verdict the controlled runner does not parse, so the check
-- raises SQLSTATE 45000: mysql exits non-zero and backend startup stops rather
-- than presenting a half-applied rebrand as success.
SET @h2_e2_brand_retired = (
  SELECT COUNT(*) = 0
    FROM nx_product
   WHERE is_deleted = 0
     AND name REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
);
SET @h2_e2_task_brand_retired = (
  SELECT COUNT(*) = 0
    FROM nx_admin_device_task
   WHERE is_deleted = 0
     AND requirement REGEXP '(^|[^[:alnum:]])[Nn]exion([^[:alnum:]]|$)'
);
SET @sql = IF(
  @h2_e2_brand_retired AND @h2_e2_task_brand_retired,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''H2_E2_RETIRED_BRAND_CLEANUP_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
