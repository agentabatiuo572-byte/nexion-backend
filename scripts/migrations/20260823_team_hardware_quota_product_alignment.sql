-- The original F4 bootstrap used retired NEX-* identifiers. The App quota
-- page consumes the canonical nx_product catalog, so repair only untouched
-- legacy bootstrap rows. Operator-edited product mappings are never replaced.
UPDATE nx_team_hardware_quota_tier
   SET product_no='stellarbox-pro',
       display_name='NexGridBox Pro',
       updated_at=NOW()
 WHERE quota_code='HW-PRO' AND product_no='NEX-NODE-PRO'
   AND is_deleted=0;

UPDATE nx_team_hardware_quota_tier
   SET product_no='stellarrack-p1',
       display_name='NexGridRack P1',
       updated_at=NOW()
 WHERE quota_code='HW-RACK-STD' AND product_no='NEX-RACK-STD'
   AND is_deleted=0;

-- The high-fidelity quota page exposes the Pro and Rack P1 gates. Retire only
-- untouched legacy tiers that have no canonical store product to navigate to.
UPDATE nx_team_hardware_quota_tier
   SET status=0,updated_at=NOW()
 WHERE product_no IN ('NEX-NODE-LITE','NEX-RACK-PRO','NEX-CLUSTER-MINI')
   AND status=1 AND is_deleted=0;
