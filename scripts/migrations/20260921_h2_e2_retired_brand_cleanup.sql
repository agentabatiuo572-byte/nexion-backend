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
--
-- 🔴 替换必须**大小写不敏感**,否则本迁移会自己把后端启动打挂:
-- 谓词用 REGEXP,而 MySQL 8 的 REGEXP 跟随列排序规则,默认 *_ci 排序规则下
-- `[Nn]exion` 连 `NEXION`/`nExIoN` 都命中;而 `REPLACE()` 是**大小写敏感**的,
-- 只认字面量 'Nexion'。于是「谓词命中 → REPLACE 改不动 → 后置断言失败 → SIGNAL
-- 45000 → 启动中止」,一行 `NEXION` 就能让整个后端起不来。
-- 用 REGEXP_REPLACE(...,'i')(与 i6 迁移同一函数)让「改得动的集合」= 「断言要求的集合」。
UPDATE nx_product
   SET name = REGEXP_REPLACE(name, '(^|[^[:alnum:]])Nexion', '$1NexGrid', 1, 0, 'i'),
       updated_at = NOW()
 WHERE is_deleted = 0
   AND name REGEXP '(^|[^[:alnum:]])[Nn]exion';

-- 🔴 `nx_admin_device_task` **不在任何 schema 或 migration 里**:它由
-- `DeviceCatalogMapper.createTaskTable()` 在应用运行时按需 CREATE TABLE IF NOT EXISTS
-- 建出来。而迁移链跑在应用启动**之前**,所以全新库上该表此刻还不存在 ——
-- 直接 UPDATE 会以 `ERROR 1146` 让整个迁移链失败,进而中止后端启动。
-- (实测:本迁移首版在只装了 scripts/schema.sql 的库上就撞到这条。)
-- 表不存在 = 没有任何任务门槛行可清理,跳过是**语义正确**的,不是掩盖错误;
-- 因此这里按表存在性分派,而不是无条件执行。
SET @h2_e2_task_table_present = (
  SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_device_task'
);
-- E2 任务门槛文案与后端白名单逐字对齐(不是简单替换:白名单是固定枚举)。
SET @h2_e2_task_sql = IF(
  @h2_e2_task_table_present = 1,
  'UPDATE nx_admin_device_task
      SET requirement = CASE requirement
            WHEN ''需 NexionBox Pro'' THEN ''需 NexGridBox Pro''
            WHEN ''需 NexionRack''    THEN ''需 NexGridRack''
            ELSE requirement
          END,
          updated_at = NOW()
    WHERE is_deleted = 0
      AND requirement IN (''需 NexionBox Pro'', ''需 NexionRack'')',
  'SELECT 1'
);
PREPARE h2_e2_task_stmt FROM @h2_e2_task_sql;
EXECUTE h2_e2_task_stmt;
DEALLOCATE PREPARE h2_e2_task_stmt;

-- Postcondition: no live row may still carry the retired brand. A bare SELECT
-- would only print a verdict the controlled runner does not parse, so the check
-- raises SQLSTATE 45000: mysql exits non-zero and backend startup stops rather
-- than presenting a half-applied rebrand as success.
SET @h2_e2_brand_retired = (
  SELECT COUNT(*) = 0
    FROM nx_product
   WHERE is_deleted = 0
     AND name REGEXP '(^|[^[:alnum:]])[Nn]exion'
);
-- 表不存在时没有任务门槛行需要断言,该合取项为真(与上面 UPDATE 的分派同一判据)。
-- 🔴 这里也必须走 PREPARE:MySQL 在**语句解析期**就解析表名,写成
-- `IF(@present=1, (SELECT ... FROM nx_admin_device_task), 1)` 时,即使分支不成立,
-- 缺表也会以 1146 直接失败(实测踩到过)。只有把整条 SELECT 放进 PREPARE 里,
-- 「不执行」才真的等于「不解析那张表」。
SET @h2_e2_task_check_sql = IF(
  @h2_e2_task_table_present = 1,
  'SELECT COUNT(*) = 0 INTO @h2_e2_task_brand_retired
     FROM nx_admin_device_task
    WHERE is_deleted = 0
      AND requirement REGEXP ''(^|[^[:alnum:]])[Nn]exion''',
  'SELECT 1 INTO @h2_e2_task_brand_retired'
);
PREPARE h2_e2_task_check FROM @h2_e2_task_check_sql;
EXECUTE h2_e2_task_check;
DEALLOCATE PREPARE h2_e2_task_check;
SET @sql = IF(
  @h2_e2_brand_retired AND @h2_e2_task_brand_retired,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''H2_E2_RETIRED_BRAND_CLEANUP_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
