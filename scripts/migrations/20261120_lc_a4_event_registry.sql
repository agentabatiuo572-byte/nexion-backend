-- Restore A4 registrations skipped by the DDL-only startup path.
-- Seed only fully absent registrations; leave existing schemas and A4 lifecycle decisions alone.
-- Sources: the historical E1/E3/E5, G1/G7, H5/H7, I1/I3, K2/K3/K4 and M5 registry migrations.
CREATE TEMPORARY TABLE tmp_lc_a4_missing_event (
  event_name VARCHAR(128) PRIMARY KEY,
  owner_domain VARCHAR(32) NOT NULL,
  family_key VARCHAR(32) NOT NULL,
  producer VARCHAR(32) NOT NULL,
  consumers VARCHAR(255) NOT NULL,
  server_authoritative TINYINT NOT NULL,
  sampling_policy VARCHAR(96) NOT NULL,
  revision INT NOT NULL,
  properties JSON NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Properties are [name, type, required flag]. All property definitions are non-PII.
INSERT INTO tmp_lc_a4_missing_event
  (event_name, owner_domain, family_key, producer, consumers, server_authoritative,
   sampling_policy, revision, properties)
VALUES
  ('admin.conversation_autopush_changed', 'admin', 'phase_admin', 'OpsSessionTemplateService', 'A2/A4/M1/M3/M5', 1, '100%', 300,
   '[["from_status","string",1],["operator","string",1],["reason","string",1],["target_id","id",1],["to_status","string",1]]'),
  ('admin.conversation_category_toggled', 'admin', 'phase_admin', 'OpsSessionTemplateService', 'A2/A4/M1/M3/M5', 1, '100%', 300,
   '[["from_status","string",1],["operator","string",1],["reason","string",1],["target_id","id",1],["to_status","string",1]]'),
  ('admin.conversation_script_published', 'admin', 'phase_admin', 'OpsSessionTemplateService', 'A2/A4/I6/M1/M3/M5', 1, '100%', 300,
   '[["from_status","string",1],["operator","string",1],["reason","string",1],["target_id","id",1],["to_status","string",1]]'),
  ('admin.conversation_template_published', 'admin', 'phase_admin', 'OpsSessionTemplateService', 'A2/A4/I6/M1/M3/M5', 1, '100%', 300,
   '[["from_status","string",1],["operator","string",1],["reason","string",1],["target_id","id",1],["to_status","string",1]]'),
  ('admin.device_deactivated', 'admin', 'device-ops', 'server', 'E5/A2/A4/L3', 0, '100%', 54,
   '[["after_status","enum",1],["before_status","enum",1],["device_id","id",1],["instance_no","id",1],["mode","enum",1],["operator","id",1],["reason","string",1],["ts","timestamp",1],["user_id","id",1]]'),
  ('admin.device_paused', 'admin', 'device-ops', 'server', 'E5/A2/A4/L3', 0, '100%', 52,
   '[["changed_count","number",1],["device_ids","json",1],["operator","id",1],["reason","string",1],["scope","enum",1],["ts","timestamp",1],["user_id","id",1]]'),
  ('admin.device_resumed', 'admin', 'device-ops', 'server', 'E5/A2/A4/L3', 0, '100%', 53,
   '[["changed_count","number",1],["device_ids","json",1],["operator","id",1],["reason","string",1],["scope","enum",1],["ts","timestamp",1],["user_id","id",1]]'),
  ('admin.product_listed', 'admin', 'phase_admin', 'server', 'E1/A2/L3/B4', 1, '100%', 40,
   '[["after_status","enum",1],["before_status","enum",1],["operator","id",1],["reason","string",1],["sku_key","id",1]]'),
  ('admin.product_price_changed', 'admin', 'phase_admin', 'server', 'E1/A2/L3/B4', 1, '100%', 42,
   '[["after","json",1],["before","json",1],["effective_at","timestamp",1],["field","enum",1],["operator","id",1],["reason","string",1],["scope","enum",1],["sku_key","id",1]]'),
  ('admin.product_unlisted', 'admin', 'phase_admin', 'server', 'E1/A2/L3/B4', 1, '100%', 41,
   '[["after_status","enum",1],["before_status","enum",1],["operator","id",1],["reason","string",1],["sku_key","id",1]]'),
  ('admin.repurchase_config_changed', 'admin', 'phase_admin', 'server', 'G7/B1/J1/A2/A4', 1, '100%', 73,
   '[["after","string",1],["before","string",1],["coverage_at_submit","number",1],["g4_capacity","number",1],["g4_ref","string",1],["g4_tickets_issued","number",1],["idempotency_key","id",1],["operator","string",1],["param_key","string",1],["reason","string",1]]'),
  ('admin.repurchase_params_changed', 'admin', 'phase_admin', 'server', 'G7/B1/J1/A2/A4', 1, '100%', 75,
   '[["after","string",1],["before","string",1],["coverage_at_submit","number",1],["g4_capacity","number",1],["g4_ref","string",1],["g4_tickets_issued","number",1],["idempotency_key","id",1],["operator","string",1],["param_key","string",1],["reason","string",1]]'),
  ('content.variant_converted', 'content', 'conversion', 'AppCopyExperimentService', 'A4/I1/B3/L2', 1, '100%', 105,
   '[["experiment_id","id",1],["order_id","id",1],["paid_or_completed_at","timestamp",1],["variant","enum",1]]'),
  ('content.variant_exposed', 'content', 'conversion', 'AppCopyExperimentService', 'A4/I1/B3/L2', 1, '100%', 104,
   '[["bucket_no","number",1],["copy_key","id",1],["copy_version","id",1],["experiment_id","id",1],["variant","enum",1]]'),
  ('daily.lucky_triggered', 'daily', 'engagement', 'AppGrowthEngagementService', 'A4/H5', 1, '100%', 95,
   '[["multiplier","number",1]]'),
  ('daily.milestone_claimed', 'daily', 'engagement', 'AppGrowthEngagementService', 'A4/H5/D4', 1, '100%', 96,
   '[["amount","number",1],["day","number",1],["reward_type","enum",1]]'),
  ('daily.spin_awarded', 'daily', 'engagement', 'AppGrowthEngagementService', 'A4/H5/H4', 1, '100%', 106,
   '[["milestone_id","id",1],["tickets","number",1]]'),
  ('milestone.fired', 'milestone', 'engagement', 'AppGrowthEngagementService', 'A4/H5/D4', 1, '100%', 97,
   '[["lifetime_earnings_usd","number",1],["milestone_id","id",1],["reward_nex","number",1],["threshold_usd","number",1]]'),
  ('notification.delivered', 'notification', 'notification', 'I3DispatchExecutor', 'A4/I3/B3/L', 1, '100%', 164,
   '[["campaign_id","id",1],["kind","enum",1],["notification_id","id",1],["priority","enum",1]]'),
  ('notification.read', 'notification', 'notification', 'AppNotificationService', 'A4/I3/L', 1, '100%', 165,
   '[["kind","enum",1],["notification_id","id",1],["priority","enum",1]]'),
  ('notification.swipe_action_taken', 'notification', 'notification', 'AppNotificationService', 'A4/I3/B3/L', 0, '100%', 166,
   '[["action","enum",1],["kind","enum",1],["notification_id","id",1],["route","string",1]]'),
  ('risk.arbitrage_suspected', 'risk', 'risk', 'OpsRiskService', 'A4/K4/H8', 1, '100%', 268,
   '[["action","enum",1],["cluster_id","id",0],["row_id","id",1],["severity","enum",1],["subject_user_ids","json",1],["view_key","enum",1]]'),
  ('risk.leaderboard_velocity_flagged', 'risk', 'risk', 'OpsRiskService', 'A4/F4d/F8/K4', 1, '100%', 270,
   '[["action","enum",1],["cluster_id","id",0],["row_id","id",1],["severity","enum",1],["subject_user_ids","json",1],["view_key","enum",1]]'),
  ('risk.trial_cycle_detected', 'risk', 'risk', 'OpsRiskService', 'A4/H2/K4', 1, '100%', 269,
   '[["cluster_id","id",0],["cycle_count","number",1],["row_id","id",1],["severity","enum",1],["subject_user_ids","json",1],["window_days","number",1]]'),
  ('risk.withdraw_escalated', 'risk', 'risk', 'AppWithdrawalService', 'A4/B5/C1/D2/K4', 1, '100%', 274,
   '[["model_version","string",1],["notify_permission","string",1],["priority","enum",1],["risk_score","number",1],["score_as_of","timestamp",1],["user_no","id",1],["withdrawal_id","id",1]]'),
  ('risk.withdraw_held', 'risk', 'risk', 'AppWithdrawalService', 'A4/D2/B5/K3/K5', 1, '100%', 167,
   '[["action","enum",1],["amount_usdt","number",1],["dimension","enum",1],["rule_id","id",1],["withdrawal_id","id",1]]'),
  ('staking.claimed', 'market', 'staking', 'server', 'G1/D4/A2/A4/App', 1, '100%', 63,
   '[["credited_usdt","number",1],["interest_usdt","number",1],["position_no","id",1],["principal_usdt","number",1],["wallet_balance_usdt","number",1]]'),
  ('staking.early_withdrawn', 'market', 'staking', 'server', 'G1/D4/A2/A4/App', 1, '100%', 64,
   '[["credited_usdt","number",1],["forfeited_interest_usdt","number",1],["penalty_usdt","number",1],["position_no","id",1],["principal_usdt","number",1],["wallet_balance_usdt","number",1]]'),
  ('tradein.completed', 'device', 'tradein', 'server', 'E3/D4/K2/A4', 1, '100%', 46,
   '[["credit_rate_pct","number",1],["discount_usdt","number",1],["order_no","id",1],["output_ratio_pct","number",1],["source_device_id","id",1],["target_device_id","id",1],["target_product_id","id",1],["tradein_no","id",1],["wallet_debit_usdt","number",1]]'),
  ('voucher.claimed', 'voucher', 'conversion', 'AppGrowthEngagementService', 'A4/H7', 1, '100%', 98,
   '[["audience","enum",1],["surface","enum",1],["voucher_id","id",1]]'),
  ('voucher.redeemed', 'voucher', 'conversion', 'AppGrowthLifecyclePublisher', 'A4/H7/E4/D4', 1, '100%', 99,
   '[["discount_usd","number",1],["order_id","id",1],["sku","id",1],["voucher_id","id",1]]');

START TRANSACTION;

DELETE seed FROM tmp_lc_a4_missing_event seed
 WHERE EXISTS (SELECT 1 FROM nx_event_schema_registry r WHERE r.event_name=seed.event_name)
    OR EXISTS (SELECT 1 FROM nx_admin_event_lifecycle l WHERE l.event_name=seed.event_name)
    OR EXISTS (SELECT 1 FROM nx_event_domain_extension x WHERE x.event_name=seed.event_name);

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
SELECT event_name, owner_domain, family_key, producer, consumers, server_authoritative,
       sampling_policy, revision, 'ACTIVE', 'migration:lc-a4', 'Restore first-install event registration', 0
  FROM tmp_lc_a4_missing_event;

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT r.id, p.property_name, p.property_type, 0, p.required_field, seed.revision, 0
  FROM tmp_lc_a4_missing_event seed
  JOIN nx_event_schema_registry r ON r.event_name=seed.event_name
  JOIN JSON_TABLE(seed.properties, '$[*]' COLUMNS (
    property_name VARCHAR(64) PATH '$[0]',
    property_type VARCHAR(32) PATH '$[1]',
    required_field TINYINT PATH '$[2]'
  )) p;

INSERT INTO nx_admin_event_lifecycle
  (event_name, lifecycle_state, version, changed_by, reason, is_deleted)
SELECT event_name, 'full', 0, 'migration:lc-a4', 'Restore first-install event lifecycle', 0
  FROM tmp_lc_a4_missing_event;

INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
SELECT x.domain_name, seed.event_name, x.producer, x.consumer, x.status,
       'migration:lc-a4', 'Restore first-install event extension', 0
  FROM (VALUES
    ROW('admin', 'admin.device_deactivated', 'OpsDeviceService', 'E5/A2/A4/L3', 'REGISTERED'),
    ROW('admin', 'admin.device_paused', 'OpsDeviceService', 'E5/A2/A4/L3', 'REGISTERED'),
    ROW('admin', 'admin.device_resumed', 'OpsDeviceService', 'E5/A2/A4/L3', 'REGISTERED'),
    ROW('admin', 'admin.repurchase_config_changed', 'OpsRepurchaseAdminService', 'G7/B1/J1/A2/A4', 'REGISTERED'),
    ROW('admin', 'admin.repurchase_params_changed', 'OpsRepurchaseAdminService', 'G7/B1/J1/A2/A4', 'REGISTERED'),
    ROW('content', 'content.variant_converted', 'AppCopyExperimentService', 'A4/I1/B3/L2', 'REGISTERED'),
    ROW('content', 'content.variant_exposed', 'AppCopyExperimentService', 'A4/I1/B3/L2', 'REGISTERED'),
    ROW('daily', 'daily.spin_awarded', 'AppGrowthEngagementService', 'A4/H5/H4', 'REGISTERED'),
    ROW('milestone', 'milestone.fired', 'AppGrowthEngagementService', 'A4/H5/D4', 'REGISTERED'),
    ROW('notification', 'notification.delivered', 'I3DispatchExecutor', 'A4/I3/B3/L', 'DONE'),
    ROW('notification', 'notification.read', 'AppNotificationService', 'A4/I3/L', 'DONE'),
    ROW('notification', 'notification.swipe_action_taken', 'AppNotificationService', 'A4/I3/B3/L', 'DONE'),
    ROW('risk', 'risk.withdraw_escalated', 'AppWithdrawalService', 'K4WithdrawalEscalationAlertConsumer', 'REGISTERED'),
    ROW('risk', 'risk.withdraw_held', 'AppWithdrawalService', 'A4/D2/B5/K3/K5', 'DONE'),
    ROW('market', 'staking.claimed', 'AppStakingService', 'G1/D4/A2/A4/App', 'REGISTERED'),
    ROW('market', 'staking.early_withdrawn', 'AppStakingService', 'G1/D4/A2/A4/App', 'REGISTERED'),
    ROW('device', 'tradein.completed', 'AppTradeinService', 'E3/D4/K2/A4', 'REGISTERED'),
    ROW('voucher', 'voucher.claimed', 'AppGrowthEngagementService', 'A4/H7', 'REGISTERED'),
    ROW('voucher', 'voucher.redeemed', 'AppGrowthLifecyclePublisher', 'A4/H7/E4/D4', 'REGISTERED')
  ) x (domain_name, event_name, producer, consumer, status)
  JOIN tmp_lc_a4_missing_event seed ON seed.event_name=x.event_name;

-- H3's forward registration already owns the schema and property revision.
INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
SELECT 'quest', r.event_name, 'QuestCompletionFactConsumer', 'A4/H3', 'REGISTERED',
       'migration:lc-a4', 'Restore H3 completion event extension', 0
  FROM nx_event_schema_registry r
 WHERE r.event_name='quest.completed' AND r.current_revision=20261103
   AND r.status='ACTIVE' AND r.is_deleted=0
   AND NOT EXISTS (SELECT 1 FROM nx_event_domain_extension x
                    WHERE x.event_name=r.event_name AND x.domain_name='quest');

UPDATE nx_event_schema_revision
   SET current_revision=GREATEST(current_revision,
       COALESCE((SELECT MAX(revision) FROM tmp_lc_a4_missing_event), current_revision))
 WHERE id=1;

COMMIT;
DROP TEMPORARY TABLE tmp_lc_a4_missing_event;
