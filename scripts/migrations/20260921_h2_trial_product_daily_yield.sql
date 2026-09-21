-- H2 试用抵扣金与商品权威日收益对齐(zentao #78)。
--
-- 现状:`AppTrialLifecycleService.safePolicy` 已经改成「商品配了收益就用商品值,否则回落
-- 到 policy.shadowDailyUSD」,但**没有任何迁移给 nx_product 写过 estimated_daily_usdt**
-- (该列 schema 默认 0)。于是 productDailyUsdt 为空,展示侧一路回落到库里存的运营旧值
-- 38.52,试用 hero 算出 3 × 38.52(封顶后 $50+)——而同一屏的 S1 商品卡写的是 $1.00/天
-- (3 天 ≈ $3)。同一次试用、两个页面、两个数,按构造必然对不上。
--
-- 权威值来自 E1 商品目录:stellarbox-s1 = 1 USDT/天、1 NEX/天
-- (scripts/sql/20260905_audit_device_projection_repair.sql 的目录回读记录)。
-- 本迁移做两件事:
--   ① 给收益仍为 0(未配置)的 S1 行写入目录权威值;
--   ② 把仍停留在旧 38.52 / 65 的试用策略值改到同一权威值 —— 只在商品值已就绪时改,
--      且只改仍等于旧值的行,不覆盖运营后来显式配置的数。
-- 幂等:第二次启动两处谓词都不再命中。

UPDATE nx_product
   SET estimated_daily_usdt = 1,
       daily_nex = 1,
       updated_at = NOW()
 WHERE product_no = 'stellarbox-s1'
   AND is_deleted = 0
   AND estimated_daily_usdt = 0
   AND daily_nex = 0;

-- 试用展示侧的回落值必须与商品一致:商品已配收益时 App 用商品值覆盖它,但一旦商品未配
-- 收益,回落值不能还是 38.52 —— 那是同一屏两个数的另一半成因。
UPDATE nx_growth_trial_policy
   SET current_value = '1',
       description = '由 E1 目标商品日收益同步'
 WHERE policy_key = 'shadowDailyUSD'
   AND is_deleted = 0
   AND current_value = '38.52'
   AND EXISTS (SELECT 1 FROM nx_product
                WHERE product_no = 'stellarbox-s1' AND is_deleted = 0
                  AND estimated_daily_usdt > 0);

UPDATE nx_growth_trial_policy
   SET current_value = '1',
       description = '由 E1 目标商品日收益同步'
 WHERE policy_key = 'shadowDailyNEX'
   AND is_deleted = 0
   AND current_value = '65'
   AND EXISTS (SELECT 1 FROM nx_product
                WHERE product_no = 'stellarbox-s1' AND is_deleted = 0
                  AND daily_nex > 0);

-- Postcondition:试用目标商品必须有正日收益,否则 hero 又会回落到运营旧值。
SET @trial_daily_ok = (
  SELECT COUNT(*) = 1
    FROM nx_product
   WHERE product_no = 'stellarbox-s1' AND is_deleted = 0
     AND estimated_daily_usdt > 0 AND daily_nex > 0
);
SET @sql = IF(
  @trial_daily_ok,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''TRIAL_PRODUCT_DAILY_YIELD_MISSING'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
