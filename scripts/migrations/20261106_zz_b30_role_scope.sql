-- B30 BE-01/02/03/43/46. Change the approved built-in role bindings only.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO nx_admin_permission
    (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES ('user_c1_export', '导出脱敏用户清单', 'API', '/users/search', 'READ', 0, 1, 0)
ON DUPLICATE KEY UPDATE status = 1, is_deleted = 0;

UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id = rp.role_id
JOIN nx_admin_permission p ON p.id = rp.permission_id
SET rp.is_deleted = 1
WHERE rp.is_deleted = 0 AND (
   (r.role_code = 'CONFIG_ADMIN' AND p.permission_code IN (
       'overview_b5_threshold_write', 'overview_b4_export', 'overview_b5_subscribe',
       'overview_b5_triage', 'PERM_AUDIT_READ', 'PERM_BI_READ',
       'PERM_CONTENT_READ', 'PERM_CONTENT_WRITE', 'PERM_DEVICES_READ',
       'PERM_DEVICES_WRITE', 'PERM_FINANCE_READ', 'PERM_FINANCE_WRITE',
       'PERM_GROWTH_READ', 'PERM_GROWTH_WRITE', 'PERM_MARKET_READ',
       'PERM_MARKET_WRITE', 'PERM_OVERVIEW_READ', 'PERM_RISK_READ',
       'PERM_RISK_WRITE', 'PERM_SYSTEM_READ', 'PERM_SYSTEM_WRITE',
       'PERM_TEAM_READ', 'PERM_TEAM_WRITE', 'PERM_USERS_READ',
       'PERM_USERS_WRITE', 'PERM_USER_READ', 'PERM_USER_WRITE'
   ))
   OR (r.role_code = 'AUDITOR' AND p.permission_code IN (
       'bi_l1_write', 'bi_l2_write', 'bi_l3_write',
       'bi_l4_export_tree', 'bi_l4_write', 'finance_d3_export',
       'overview_b3_export', 'overview_b4_export', 'user_c1_write'
   ))
   OR (r.role_code = 'FINANCE' AND p.permission_code IN (
       'finprod_g3_engine_pause_toggle', 'finprod_g3_override_price_write', 'finprod_g4_dividend_rate_write',
       'overview_b5_threshold_write', 'platform_a2_export', 'platform_a2_read',
       'platform_a3_read', 'user_c2_account_freeze', 'user_c2_account_unfreeze',
       'user_c2_read', 'user_c2_session_revoke_all', 'finprod_g1_apy_write',
       'finprod_g1_kill_toggle', 'finprod_g1_min_write', 'finprod_g1_penalty_write',
       'finprod_g1_write', 'bi_l3_export_detail', 'emergency_j1_gate_kill',
       'finance_d4_export', 'finprod_g2_cap_per_tx_write', 'finprod_g2_cap_platform_write',
       'finprod_g2_cap_user_write', 'finprod_g2_fee_rate_write', 'finprod_g2_queue_cancel',
       'finprod_g2_swap_toggle', 'finprod_g3_curve_pump_prob_write', 'finprod_g3_curve_target_price_write',
       'finprod_g3_write', 'finprod_g4_airdrop_lock_days_write', 'finprod_g4_airdrop_pct_write',
       'finprod_g4_emission_curve_write', 'finprod_g4_market_toggle', 'finprod_g4_price_write',
       'finprod_g4_royalty_write', 'finprod_g4_write', 'finprod_g7_apy_write',
       'finprod_g7_nurture_write', 'finprod_g7_write', 'overview_b4_export'
   ))
   OR (r.role_code = 'FINANCE_LEAD' AND p.permission_code IN (
       'overview_b5_threshold_write', 'platform_a2_export', 'platform_a2_read',
       'platform_a3_read', 'user_c2_account_freeze', 'user_c2_account_unfreeze',
       'user_c2_read', 'user_c2_session_revoke_all', 'finprod_g1_apy_write',
       'finprod_g1_kill_toggle', 'finprod_g1_min_write', 'finprod_g1_penalty_write',
       'finprod_g1_write', 'emergency_j1_gate_kill', 'finprod_g4_airdrop_lock_days_write',
       'finprod_g4_airdrop_pct_write', 'finprod_g4_emission_curve_write', 'finprod_g4_market_toggle',
       'finprod_g4_price_write', 'finprod_g4_royalty_write', 'finprod_g4_write',
       'overview_b4_export'
   ))
   OR (r.role_code = 'GROWTH' AND p.permission_code IN (
       'device_e4_order_refund', 'finprod_g1_apy_write', 'finprod_g1_kill_toggle',
       'finprod_g1_min_write', 'finprod_g1_penalty_write', 'finprod_g1_write',
       'finprod_g2_cap_per_tx_write', 'finprod_g2_cap_platform_write', 'finprod_g2_cap_user_write',
       'finprod_g2_fee_rate_write', 'finprod_g2_queue_cancel', 'finprod_g2_swap_toggle',
       'finprod_g2_write', 'finprod_g3_curve_pump_prob_write', 'finprod_g3_curve_target_price_write',
       'finprod_g3_engine_pause_toggle', 'finprod_g3_override_price_write', 'finprod_g3_write',
       'finprod_g4_airdrop_lock_days_write', 'finprod_g4_airdrop_pct_write', 'finprod_g4_dividend_rate_write',
       'finprod_g4_emission_curve_write', 'finprod_g4_market_toggle', 'finprod_g4_price_write',
       'finprod_g4_royalty_write', 'finprod_g4_write', 'finprod_g7_apy_write',
       'finprod_g7_nurture_write', 'finprod_g7_write', 'network_f1_promote_user',
       'network_f1_reward_reissue', 'network_f1_reward_reverse', 'network_f3_engine_pause',
       'network_f4_ambassador_approve', 'network_f4_pool_fund', 'overview_b5_threshold_write',
       'platform_a3_read', 'bi_l4_export_tree', 'device_e1_generation_gate_force_lock',
       'device_e1_generation_gate_force_unlock', 'device_e2_phone_tier_nex', 'device_e2_phone_tier_usdt',
       'device_e2_write', 'device_e6_flag_toggle', 'growth_h1_control_pin_write',
       'growth_h1_override_revoke', 'growth_h1_write', 'growth_h8_write',
       'network_f1_permanent_protection', 'network_f2_policy_amplify', 'network_f2_royalty_rate',
       'network_f2_write', 'network_f3_match_rate', 'network_f3_write',
       'overview_b4_export', 'overview_b5_subscribe', 'overview_b5_triage',
       'user_c1_write'
   ))
   OR (r.role_code = 'RISK' AND p.permission_code IN (
       'overview_b5_threshold_write', 'platform_a3_read', 'overview_b4_export',
       'PERM_ADMIN_READ', 'PERM_ADMIN_ROLE_ASSIGN', 'PERM_ADMIN_WRITE',
       'PERM_ROLE_READ', 'user_c1hub_2fa_reset', 'user_c1hub_account_freeze',
       'user_c1hub_account_unfreeze', 'user_c1hub_compensation_grant', 'user_c1hub_device_recycle',
       'user_c1hub_device_replace', 'user_c1hub_earning_grant', 'user_c1hub_earning_reverse',
       'user_c1hub_password_force_change', 'user_c1hub_password_reset', 'user_c1hub_session_revoke_all',
       'user_c1hub_session_revoke_one'
   ))
   OR (r.role_code = 'CONTENT' AND p.permission_code IN (
       'content_i3_critical_send', 'content_i5_disclosure_publish', 'content_i5_gate_adjust',
       'content_legal_terms_publish', 'overview_b5_threshold_write', 'platform_a2_read',
       'content_i1_experiment_manage', 'content_i4_publish_standard', 'content_i4_trust_section_manage',
       'overview_b4_export', 'overview_b5_subscribe', 'overview_b5_triage'
   ))
);

UPDATE nx_admin_role_menu rm
JOIN nx_admin_role r ON r.id = rm.role_id
JOIN nx_admin_menu m ON m.id = rm.menu_id
SET rm.is_deleted = 1
WHERE rm.is_deleted = 0 AND (
    (r.role_code IN ('CONFIG_ADMIN', 'CONTENT') AND m.menu_code IN ('B1', 'B2'))
 OR (r.role_code IN ('FINANCE', 'FINANCE_LEAD', 'GROWTH', 'RISK') AND m.menu_code = 'A3')
 OR (r.role_code = 'RISK' AND m.menu_code IN (
         'I4', 'I5', 'MENU_CONTENT_I4', 'MENU_CONTENT_I5', 'MENU_HOME', 'MENU_UMS',
         'MENU_UMS_ADMIN', 'MENU_UMS_MENU', 'MENU_UMS_PERMISSION', 'MENU_UMS_ROLE'))
 OR (r.role_code IN ('RISK', 'AUDITOR') AND m.menu_code = 'M3')
);

-- D10 grants include the read-only policy catalog needed to edit a draft.
INSERT INTO nx_admin_role_permission (role_id, permission_id, is_deleted)
SELECT r.id, p.id, 0
FROM nx_admin_role r
JOIN nx_admin_permission p
WHERE r.status = 1 AND r.is_deleted = 0 AND p.status = 1 AND p.is_deleted = 0
  AND (
       (r.role_code IN ('SUPER_ADMIN', 'AUDITOR', 'GROWTH') AND p.permission_code = 'user_c1_export')
    OR (r.role_code = 'GROWTH' AND p.permission_code IN (
        'growth_promotion_edit', 'growth_promotion_metrics_read', 'growth_promotion_policy_read',
        'growth_promotion_read', 'growth_promotion_reward_read', 'growth_promotion_simulate',
        'growth_promotion_submit'
    ))
    OR (r.role_code = 'FINANCE_LEAD' AND p.permission_code IN ('device_e4_read', 'device_e4_order_refund'))
    OR (r.role_code IN ('FINANCE', 'FINANCE_LEAD') AND p.permission_code = 'platform_a2_proposal_create')
  )
ON DUPLICATE KEY UPDATE is_deleted = 0;

INSERT INTO nx_admin_role_menu (role_id, menu_id, is_deleted)
SELECT r.id, m.id, 0
FROM nx_admin_role r
JOIN nx_admin_menu m ON m.menu_code IN ('E', 'E4')
WHERE r.role_code = 'FINANCE_LEAD' AND r.status = 1 AND r.is_deleted = 0
  AND m.status = 1 AND m.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0;

-- C1 export publishes this existing audit event in the same transaction.
INSERT INTO nx_event_schema_registry
    (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
     sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES ('admin.user_list_exported', 'admin', 'phase_admin', 'server', 'A2/L5', 1,
        '100%', 24, 'ACTIVE', 'migration:c1', 'Register C1 masked list-export evidence', 0)
ON DUPLICATE KEY UPDATE status = 'ACTIVE', is_deleted = 0;

INSERT INTO nx_event_schema_property
    (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
FROM nx_event_schema_registry s
JOIN (
    SELECT 'filter_hash' property_name, 'string' property_type
    UNION ALL SELECT 'row_count', 'number'
    UNION ALL SELECT 'exporter_operator', 'id'
    UNION ALL SELECT 'exporter_role', 'enum'
    UNION ALL SELECT 'occurred_at', 'timestamp'
) p
WHERE s.event_name = 'admin.user_list_exported'
ON DUPLICATE KEY UPDATE is_deleted = 0;

INSERT INTO nx_event_schema_revision (id, current_revision)
VALUES (1, 24)
ON DUPLICATE KEY UPDATE current_revision = GREATEST(current_revision, 24);

COMMIT;
