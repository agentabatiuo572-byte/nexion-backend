-- HDPay 联调测试 SKU 的清退(zentao #92)。
--
-- 现状:`StorefrontProductPublishGate` 已接入四条用户可见路径,判据是「测试标识
-- (test|mock|demo|e2e|smoke|fixture) **或** 两种货币都无有效收益」。契约测试
-- `StorefrontProductPublishGateContractTest` 明确覆盖了本单那一行:
--   gate("hd1-0902", "HDPay1U", "0", "0") → NO_EFFECTIVE_EARNINGS_REASON
-- 但公网 test 的商城「更多型号」仍展示 HDPay1U(售价 $1、日产 $0、库存 0)——
-- 说明库里那行的收益列**不是 0**(门按「有收益」放行),或产品号/名称与判据不符。
--
-- 该 SKU 是支付联调专用,不属于对外商品目录。本迁移把它明确移出用户可见面:
--   ① 收益清零,使发布门在任何查询路径上都能拦住它(即使状态被人改回);
--   ② 状态改为 DRAFT,与 E1 商品目录「非在售」的既有取值一致
--      (DeviceCatalogMapper 把 ACTIVE/ON_SALE 映射为 on,其余不为 on)。
-- 只动这一个产品号,幂等:第二次启动谓词不再命中。
--
-- 🔴 不改任何订单/持仓/账本金额 —— 该 SKU 若已有成交,历史行必须原样保留。

UPDATE nx_product
   SET estimated_daily_usdt = 0,
       daily_nex = 0,
       updated_at = NOW()
 WHERE product_no = 'hd1-0902'
   AND is_deleted = 0
   AND (COALESCE(estimated_daily_usdt,0) <> 0 OR COALESCE(daily_nex,0) <> 0);

UPDATE nx_product
   SET status = 'DRAFT',
       updated_at = NOW()
 WHERE product_no = 'hd1-0902'
   AND is_deleted = 0
   AND UPPER(COALESCE(status,'')) IN ('ACTIVE', 'ON_SALE', 'ON');

-- Postcondition:该测试 SKU 必须被发布门拦住(收益全零)。若仍有人把它配出收益,
-- 这里让启动失败而不是让联调商品重新出现在用户目录里。
SET @hdpay_test_blocked = (
  SELECT COUNT(*) = 0
    FROM nx_product
   WHERE product_no = 'hd1-0902' AND is_deleted = 0
     AND (COALESCE(estimated_daily_usdt,0) > 0 OR COALESCE(daily_nex,0) > 0)
);
SET @sql = IF(
  @hdpay_test_blocked,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''HDPAY_TEST_SKU_STILL_PUBLISHABLE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
