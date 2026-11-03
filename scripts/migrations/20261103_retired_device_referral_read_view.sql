CREATE OR REPLACE SQL SECURITY INVOKER VIEW nx_visible_direct_referral AS
 SELECT d.* FROM nx_direct_referral_settlement d
 WHERE NOT EXISTS (
   SELECT 1 FROM nx_compute_retired_task a
   WHERE d.source_type='direct_device_earning' AND d.source_environment='PRODUCTION' AND d.run_id=''
     AND a.user_id=d.source_user_id AND a.receipt_no=d.source_ref COLLATE utf8mb4_0900_ai_ci
 );
