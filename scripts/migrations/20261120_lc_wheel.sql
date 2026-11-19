-- LC-03: seed H4 configuration without publishing or changing an event.
START TRANSACTION;

SET @lc_wheel_pool_locked = EXISTS (
    SELECT 1 FROM nx_audit_object_lock
     WHERE target_domain='H' AND target_type='h4_wheel_pool' AND target_id='ACTIVE_POOL'
       AND is_deleted=0
);

-- Keep tier IDs and keys stable for spin history and public receipts.
UPDATE nx_growth_wheel_tier
   SET reward_name='+10 UVT', reward_kind='uvt', reward_amount=10
 WHERE NOT @lc_wheel_pool_locked
   AND BINARY tier_name='points-50' AND BINARY reward_name='+50 points'
   AND probability_pct=24 AND real_outflow=0 AND BINARY reward_kind='points'
   AND reward_amount=50 AND voucher_id IS NULL AND daily_stock=0 AND sort_order=20
   AND status=1 AND is_deleted=0;

UPDATE nx_growth_wheel_tier
   SET reward_name='$100 USDT', reward_amount=100
 WHERE NOT @lc_wheel_pool_locked
   AND BINARY tier_name='usdt-500' AND BINARY reward_name='$500 USDT'
   AND probability_pct=0.1 AND real_outflow=1 AND BINARY reward_kind='usdt'
   AND reward_amount=500 AND voucher_id IS NULL AND daily_stock=5 AND sort_order=80
   AND status=1 AND is_deleted=0;

-- Match the repaired tier so this note can also be corrected on a later run.
UPDATE nx_growth_wheel_guard g
  JOIN nx_growth_wheel_tier t
    ON BINARY t.tier_name='usdt-500' AND BINARY t.reward_name='$100 USDT'
   AND BINARY t.reward_kind='usdt' AND t.reward_amount=100 AND t.daily_stock=5
   AND t.status=1 AND t.is_deleted=0
   SET g.note='100 USDT x5; 20 USDT x50; coupon x200'
 WHERE NOT @lc_wheel_pool_locked
   AND BINARY g.guard_key='cap' AND BINARY g.guard_label='Per-tier daily stock'
   AND BINARY g.guard_value='tier.daily_stock' AND BINARY g.note='500 USDT x5; 20 USDT x50; coupon x200'
   AND g.sort_order=20 AND g.status=1 AND g.is_deleted=0
   AND NOT EXISTS (
       SELECT 1 FROM nx_audit_object_lock
        WHERE target_domain='H' AND target_type='h4_wheel_guard' AND target_id='cap'
          AND is_deleted=0
   );

INSERT INTO nx_growth_wheel_tier
    (tier_name,reward_name,probability_pct,real_outflow,reward_kind,reward_amount,
     voucher_id,daily_stock,sort_order,status,is_deleted)
WITH defaults AS (
    SELECT 'comfort-nex-5' AS tier_name, '+5 UVT' AS reward_name, 38.0000 AS probability_pct,
           0 AS real_outflow, 'uvt' AS reward_kind, 5 AS reward_amount,
           NULL AS voucher_id, 0 AS daily_stock, 10 AS sort_order
    UNION ALL SELECT 'points-50', '+10 UVT', 24.0000, 0, 'uvt', 10, NULL, 0, 20
    UNION ALL SELECT 'nex-30', '+30 UVT', 18.0000, 0, 'uvt', 30, NULL, 0, 30
    UNION ALL SELECT 'nex-150', '+150 UVT', 11.0000, 0, 'uvt', 150, NULL, 0, 40
    UNION ALL SELECT 'usdt-1', '$1 USDT', 5.0000, 1, 'usdt', 1, NULL, 0, 50
    UNION ALL SELECT 'coupon-50', '$50 device coupon', 3.0000, 0, 'coupon', 50, 'H4-WHEEL-DEVICE-50', 200, 60
    UNION ALL SELECT 'usdt-20', '$20 USDT', 0.9000, 1, 'usdt', 20, NULL, 50, 70
    UNION ALL SELECT 'usdt-500', '$100 USDT', 0.1000, 1, 'usdt', 100, NULL, 5, 80
), missing AS (
    SELECT d.* FROM defaults d
     WHERE NOT EXISTS (SELECT 1 FROM nx_growth_wheel_tier t WHERE t.tier_name=d.tier_name)
)
SELECT tier_name,reward_name,probability_pct,real_outflow,reward_kind,reward_amount,
       voucher_id,daily_stock,sort_order,1,0
  FROM missing
 WHERE NOT @lc_wheel_pool_locked
   -- An existing pool may already use the full 100% with operator-defined tiers.
   AND (SELECT COALESCE(SUM(probability_pct),0) FROM nx_growth_wheel_tier WHERE status=1 AND is_deleted=0)
       + (SELECT COALESCE(SUM(probability_pct),0) FROM missing) <= 100
 ORDER BY sort_order;

UPDATE nx_growth_wheel_guard
   SET guard_value='200'
 WHERE BINARY guard_key='budget' AND BINARY guard_value='2000'
   AND BINARY guard_label='Daily real payout budget USD' AND BINARY note='UTC global real USDT payout cap'
   AND sort_order=10 AND status=1 AND is_deleted=0
   AND NOT EXISTS (
       SELECT 1 FROM nx_audit_object_lock
        WHERE target_domain='H' AND target_type='h4_wheel_guard' AND target_id='budget'
          AND is_deleted=0
   );

INSERT INTO nx_growth_wheel_guard (guard_key,guard_label,guard_value,note,sort_order,status,is_deleted)
SELECT d.guard_key,d.guard_label,d.guard_value,d.note,d.sort_order,1,0
  FROM (
    SELECT 'budget' AS guard_key, 'Daily real payout budget USD' AS guard_label,
           '200' AS guard_value, 'UTC global real USDT payout cap' AS note, 10 AS sort_order
    UNION ALL SELECT 'cap', 'Per-tier daily stock', 'tier.daily_stock', '100 USDT x5; 20 USDT x50; coupon x200', 20
    UNION ALL SELECT 'kill', 'Real prize enabled', 'on', 'off downgrades real tiers to comfort tier', 30
  ) d
 WHERE NOT EXISTS (SELECT 1 FROM nx_growth_wheel_guard g WHERE g.guard_key=d.guard_key)
   AND NOT EXISTS (
       SELECT 1 FROM nx_audit_object_lock l
        WHERE l.target_domain='H' AND l.target_type='h4_wheel_guard' AND l.target_id=d.guard_key
          AND l.is_deleted=0
   )
 ORDER BY d.sort_order;

INSERT INTO nx_growth_voucher
    (voucher_id,voucher_name,voucher_type,amount_usd,percent_value,min_purchase_usd,max_discount_usd,
     applicable_skus,audience,start_at,end_at,claim_surfaces,popup_enabled,stack_with_trial,
     stack_with_others,splittable,status,created_by,updated_by,is_deleted)
SELECT 'H4-WHEEL-DEVICE-50','Lucky Spin $50 Device Coupon','fixed',50,0,50,50,
       JSON_ARRAY(),'all',0,0,JSON_ARRAY(),0,0,0,0,'active','migration:h4-wheel','migration:h4-wheel',0
 WHERE NOT EXISTS (SELECT 1 FROM nx_growth_voucher WHERE voucher_id='H4-WHEEL-DEVICE-50')
   AND NOT EXISTS (
       SELECT 1 FROM nx_audit_object_lock
        WHERE target_domain='H' AND target_type='voucher' AND target_id='H4-WHEEL-DEVICE-50'
          AND is_deleted=0
   );

COMMIT;
