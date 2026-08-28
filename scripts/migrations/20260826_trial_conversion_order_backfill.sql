-- Restore the commerce fact chain for legacy trial purchases that already
-- debited the wallet and created a production device before TRIAL_CONVERT
-- orders became mandatory. This migration never touches wallets or stock.
START TRANSACTION;

INSERT INTO nx_order(
  user_id,order_no,product_id,quantity,order_type,item_count,
  subtotal_usdt,discount_usdt,amount_usdt,payment_status,order_status,
  activation_status,paid_at,created_at,updated_at,is_deleted
)
SELECT
  d.user_id,
  CONCAT('TRC-LEGACY-', d.id),
  p.id,
  1,
  'TRIAL_CONVERT',
  1,
  d.price_usdt_snapshot,
  LEAST(d.price_usdt_snapshot,
        GREATEST(d.price_usdt_snapshot - charged.amount, 0)),
  charged.amount,
  'PAID',
  'PAID',
  'ACTIVE',
  charged.paid_at,
  charged.paid_at,
  charged.paid_at,
  0
FROM nx_user_device d
JOIN (
  SELECT user_id, SUBSTRING_INDEX(biz_no, ':CHARGE', 1) legacy_claim_no,
         MAX(amount) amount, MAX(created_at) paid_at
    FROM nx_wallet_ledger
   WHERE biz_type='TRIAL_CHARGE' AND asset='USDT' AND direction='OUT'
     AND biz_no LIKE 'TRIAL-%:CHARGE'
   GROUP BY user_id, SUBSTRING_INDEX(biz_no, ':CHARGE', 1)
) charged
  ON charged.user_id=d.user_id AND charged.legacy_claim_no=d.source_order_no
JOIN nx_product p
  ON p.product_no=CASE WHEN d.product_code='device-trial-standard'
                       THEN 'stellarbox-s1' ELSE d.product_code END
 AND p.is_deleted=0
LEFT JOIN nx_order existing
  ON existing.order_no=CONCAT('TRC-LEGACY-', d.id) AND existing.is_deleted=0
WHERE d.is_deleted=0 AND d.source_environment='PRODUCTION'
  AND d.source_channel='TRIAL' AND d.source_order_no LIKE 'TRIAL-%'
  AND d.price_usdt_snapshot>0 AND charged.amount>=0
  AND charged.amount<=d.price_usdt_snapshot
  AND existing.id IS NULL;

INSERT INTO nx_order_item(
  order_no,product_id,product_no,product_name,quantity,
  unit_price_usdt,line_amount_usdt,sort_order,created_at,updated_at,is_deleted
)
SELECT
  o.order_no,p.id,p.product_no,p.name,1,
  d.price_usdt_snapshot,d.price_usdt_snapshot,0,o.created_at,o.created_at,0
FROM nx_user_device d
JOIN nx_order o
  ON o.order_no=CONCAT('TRC-LEGACY-', d.id)
 AND o.order_type='TRIAL_CONVERT' AND o.payment_status='PAID' AND o.is_deleted=0
JOIN nx_product p ON p.id=o.product_id AND p.is_deleted=0
LEFT JOIN nx_order_item existing
  ON existing.order_no=o.order_no AND existing.is_deleted=0
WHERE d.is_deleted=0 AND d.source_order_no LIKE 'TRIAL-%'
  AND existing.id IS NULL;

UPDATE nx_trial_claim c
JOIN nx_user_device d
  ON d.id=c.user_device_id AND d.source_order_no=c.claim_no AND d.is_deleted=0
JOIN nx_order o
  ON o.order_no=CONCAT('TRC-LEGACY-', d.id)
 AND o.order_type='TRIAL_CONVERT' AND o.payment_status='PAID' AND o.is_deleted=0
SET c.settlement_snapshot=CONCAT(
      COALESCE(NULLIF(c.settlement_snapshot,''), 'trigger=legacy'),
      ',orderNo=',o.order_no
    ),
    c.updated_at=NOW()
WHERE c.is_deleted=0 AND c.status='REDEEMED'
  AND (c.settlement_snapshot IS NULL OR c.settlement_snapshot NOT LIKE '%orderNo=%');

UPDATE nx_user_device d
JOIN nx_order o
  ON o.order_no=CONCAT('TRC-LEGACY-', d.id)
 AND o.order_type='TRIAL_CONVERT' AND o.payment_status='PAID' AND o.is_deleted=0
JOIN nx_product p ON p.id=o.product_id AND p.is_deleted=0
SET d.source_order_no=o.order_no,
    d.product_id=p.id,
    d.product_code=p.product_no,
    d.product_tier=p.tier,
    d.updated_at=NOW()
WHERE d.is_deleted=0 AND d.source_order_no LIKE 'TRIAL-%';

COMMIT;
