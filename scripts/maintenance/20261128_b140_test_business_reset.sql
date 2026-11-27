-- B140 plan B. DML for the explicit maintenance transaction on local and shared TEST.
-- The runner must stop writers, verify the target and backup, check financial and
-- Compute V2 preconditions, and write the once marker with the catalog updates.

-- Retain delivered message deduplication. Retire old events from publisher and
-- outbox replay, and retain the original facts for investigation.
UPDATE nx_event_outbox
   SET is_deleted=1
 WHERE is_deleted=0
   AND aggregate_type IN ('ORDER','E4_ORDER','USER_DEVICE','DEVICE','COMPUTE_TASK','TRIAL',
                          'TRIAL_CLAIM','TRADEIN','TRADE_IN','E3_TRADEIN','PROMOTION_ORDER',
                          'PROMOTION_REWARD','REFERRAL_REWARD_SETTLEMENT','VOUCHER_GRANT',
                          'EVENT_QUEST','EARNING_MILESTONE');

-- Store a definitive application denial so a client holding an old command
-- can clear it. Replaying the original key returns the denial without writing.
UPDATE nx_admin_idempotency_record
   SET status='SUCCEEDED', response_json=JSON_OBJECT('code',409,'message','B140_TEST_BUSINESS_RESET'),
       rollback_confirmed=0, error_message=NULL
 WHERE is_deleted=0
   AND scope REGEXP CONCAT(
       '^APP:(ORDER_CREATE|BUNDLE_ORDER_CREATE|ORDER_CANCEL|ORDER_HIDE|ORDER_PAYMENT',
       '|TRIAL_START|TRIAL_CANCEL|TRIAL_OPS_CANCEL|TRIAL_EXTEND|TRIAL_REDEEM_EARLY',
       '|TRIAL_CHARGE|TRIAL_SETTLE_DUE|TRIAL_CONVERT:PRODUCTION',
       '|E3_TRADEIN_SUBMIT|E3_CAPACITY_REPLACE|E3_CAPACITY_KEEP',
       '|VOUCHER_CLAIM|WHEEL_SPIN|EVENT_JOIN|EVENT_CLAIM|EVENT_REMINDER):USER:[0-9]+$');

UPDATE nx_payment_command
   SET status='REJECTED',
       response_json=JSON_OBJECT('code',409,'message','B140_TEST_BUSINESS_RESET'),
       updated_at=FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
 WHERE kind IN ('order-pay','trial-convert','trade-in','capacity-replace','capacity-keep');

-- Promotion roots and children are business data. Global promotion execution
-- policies, H4 event rules, wheel tiers and voucher definitions are configuration.
DELETE FROM nx_promotion_export_job;
DELETE FROM nx_promotion_report_snapshot;
DELETE FROM nx_promotion_list_snapshot;
DELETE FROM nx_promotion_device_receipt;
DELETE FROM nx_promotion_reward_attempt;
DELETE FROM nx_promotion_reversal;
DELETE FROM nx_promotion_refund_hold;
DELETE FROM nx_promotion_reward;
DELETE FROM nx_promotion_order_receipt;
DELETE FROM nx_promotion_reservation;
DELETE FROM nx_promotion_usage;
DELETE FROM nx_promotion_budget;
DELETE FROM nx_promotion_reward_identity;
DELETE FROM nx_promotion_rule_identity;
DELETE FROM nx_promotion_version;
DELETE FROM nx_promotion_quote;
DELETE FROM nx_promotion;
DELETE FROM nx_voucher_popup_state;
DELETE FROM nx_growth_voucher_grant;
DELETE FROM nx_growth_wheel_spin;
DELETE FROM nx_growth_spin_ticket;
DELETE FROM nx_growth_event_claim;
DELETE FROM nx_growth_event_pool_result;
DELETE FROM nx_growth_event_region;
DELETE FROM nx_growth_event_reminder;
DELETE FROM nx_user_event_quest;
-- Keep the closeout marker so the scheduler cannot settle an old campaign again.
UPDATE nx_growth_event_rule
   SET spent_nex=0, spent_usdt=0, spent_devices=0, spent_tickets=0
 WHERE spent_nex<>0 OR spent_usdt<>0 OR spent_devices<>0 OR spent_tickets<>0;

-- The wallet, deposit and payout channels keep their provider evidence. A
-- direct-purchase quote that already has an intent is part of that evidence.
DELETE q FROM nx_purchase_payment_quote q
LEFT JOIN nx_purchase_payment_intent p ON p.quote_id=q.quote_id
WHERE p.intent_no IS NULL;
DELETE FROM nx_payment_record
 WHERE provider IN ('UVEL_WALLET','USDT_WALLET','UVEL_USDT_WALLET','VOUCHER');
DELETE FROM nx_purchase_e1_hold;
DELETE FROM nx_team_hardware_quota_usage;
DELETE FROM nx_growth_trial_daily_quota;
DELETE FROM nx_trial_external_rejection;
DELETE FROM nx_trial_balance_submission;
DELETE FROM nx_trial_claim;
DELETE FROM nx_tradein_application;
DELETE FROM nx_trade_in_order;
DELETE FROM nx_device_slot_replacement;
DELETE FROM nx_user_sku_entitlement;
DELETE FROM nx_order_state_history;
DELETE FROM nx_order_item;
DELETE FROM nx_order;
DELETE FROM nx_admin_device_order;

-- Each charged finite unit decreased available stock and increased sold count.
-- The display sold baseline is authored storefront configuration.
UPDATE nx_product
   SET stock=CASE WHEN inventory_mode='FINITE' THEN stock+sold_count ELSE stock END,
       sold_count=0
 WHERE sold_count<>0;
UPDATE nx_admin_device_sku
   SET purchase_gate_json=JSON_SET(purchase_gate_json,'$.quotaSold',0)
 WHERE JSON_VALID(purchase_gate_json)=1
   AND JSON_EXTRACT(purchase_gate_json,'$.quotaSold') IS NOT NULL;
UPDATE nx_admin_device_sku SET sold=0 WHERE sold<>0;

-- Clear device ownership and execution state. Native app login and installed
-- device keys are independent of the bound business device.
DELETE FROM nx_phone_replacement_grant;
DELETE FROM nx_phone_replacement_request;
DELETE FROM nx_phone_replacement_verification;
DELETE FROM nx_phone_replacement_event;
DELETE FROM nx_phone_replacement_operation;
DELETE FROM nx_phone_replacement_history_state;
DELETE FROM nx_phone_binding;
DELETE FROM nx_test_cloud_zero_credit_resolution;
DELETE FROM nx_test_cloud_stability_recovery_use;
DELETE FROM nx_test_cloud_request;
DELETE FROM nx_test_cloud_resource_guard;
DELETE FROM nx_test_cloud_legacy_admission;
DELETE FROM nx_test_cloud_execution;
DELETE FROM nx_test_cloud_binding;
DELETE FROM nx_compute_share_enrollment;
DELETE FROM nx_sim_device_target_cycle;
DELETE FROM nx_simulated_task;
DELETE FROM nx_simulated_device_runtime;
DELETE FROM nx_compute_device_task_lock;
DELETE FROM nx_compute_receipt;
DELETE FROM nx_compute_task;
DELETE FROM nx_compute_retired_ledger;
DELETE FROM nx_compute_retired_task;
DELETE FROM nx_business_device_alert_state;
DELETE FROM nx_user_device_runtime;
DELETE FROM nx_user_device;

-- Derived order/device settlements. Team membership and operating rules remain
-- useful for retained accounts and the first order under the new identifiers.
DELETE FROM nx_binary_auto_settlement_retry;
DELETE FROM nx_binary_auto_settlement_batch;
DELETE FROM nx_binary_platform_allocation;
DELETE FROM nx_binary_platform_period;
DELETE FROM nx_binary_commission_settlement;
DELETE FROM nx_binary_paid_order_volume;
DELETE FROM nx_binary_volume_cursor;
DELETE FROM nx_commission_operation;
DELETE FROM nx_commission_event;
DELETE FROM nx_unilevel_event_recovery;
DELETE FROM nx_unilevel_order_settlement;
DELETE FROM nx_direct_referral_settlement;
DELETE FROM nx_earning_push_task;
DELETE FROM nx_earning_milestone;
DELETE FROM nx_earning_event;
DELETE FROM nx_earning_summary;
DELETE FROM nx_earnings_source_recovery;
-- The verified test reset still replays the original earnings credit. Keep its
-- source row alongside the append-only reset and original receipt records.
DELETE e FROM nx_earnings_release_entry e
LEFT JOIN nx_b140_test_wallet_reset r ON r.entry_no=e.entry_no COLLATE utf8mb4_bin
WHERE r.entry_no IS NULL;
DELETE FROM nx_wallet_bill;
DELETE FROM nx_wallet_ledger;

-- Preserve wallet identity and Support's append-only balance history. Existing
-- wallet triggers record this change and advance their own monotonic counters.
UPDATE nx_user_wallet
   SET usdt_available=0, nex_available=0, pending_withdraw=0, cregis_risk_held=0,
       lifetime_earned=0, cumulative_deposit_usdt=0, version=version+1
 WHERE usdt_available<>0 OR nex_available<>0 OR pending_withdraw<>0 OR cregis_risk_held<>0
    OR lifetime_earned<>0 OR cumulative_deposit_usdt<>0;
