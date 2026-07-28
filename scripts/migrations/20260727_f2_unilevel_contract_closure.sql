-- F2 canonical contract closure:
-- L1 is the single immutable 10% direct royalty source. Correct historical
-- drift before exposing the same rule table to the App public projection.
UPDATE nx_commission_rule
   SET usdt_rate = 0.100000,
       updated_at = NOW()
 WHERE is_deleted = 0
   AND status = 1
   AND LOWER(commission_type) = 'unilevel'
   AND layer_no = 1
   AND usdt_rate <> 0.100000;
