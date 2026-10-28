-- H2 trial targets must reference the canonical E1 nx_product product_no.
-- This migration is intentionally narrow: it repairs only the shipped legacy
-- alias/name and never overwrites an operator-selected product or custom name.

START TRANSACTION;

UPDATE nx_growth_trial_policy
   SET current_value = 'stellarbox-s1',
       description = '从 E1 在售实物 SKU 选择',
       updated_at = NOW()
 WHERE policy_key = 'trialProductId'
   AND current_value = 'device-trial-standard'
   AND is_deleted = 0;

UPDATE nx_growth_trial_policy policy
JOIN nx_growth_trial_policy target
  ON target.policy_key = 'trialProductId'
 AND target.is_deleted = 0
JOIN nx_product product
  ON product.product_no = target.current_value
 AND product.is_deleted = 0
   SET policy.current_value = TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(product.price_usdt AS CHAR))),
       policy.description = '由 E1 目标商品售价同步',
       policy.updated_at = NOW()
 WHERE policy.policy_key = 'trialPriceUSD'
   AND policy.is_deleted = 0
   AND product.price_usdt > 0
   AND (
     policy.current_value <> TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(product.price_usdt AS CHAR)))
     OR policy.description <> '由 E1 目标商品售价同步'
   );

UPDATE nx_product
   SET name = 'NexGridBox S1',
       updated_at = NOW()
 WHERE product_no = 'stellarbox-s1'
   AND name IN ('NexionBox S1', 'StellarBox S1')
   AND is_deleted = 0;

COMMIT;
