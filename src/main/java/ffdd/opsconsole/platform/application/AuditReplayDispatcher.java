package ffdd.opsconsole.platform.application;
import ffdd.opsconsole.content.application.LearningCourseActivationApproval;
import ffdd.opsconsole.device.application.E2TaskPricingApproval;
import ffdd.opsconsole.growth.application.H8RewardParamApproval;
import ffdd.opsconsole.growth.application.H5GrowthApproval;
import ffdd.opsconsole.growth.application.H3GrowthApproval;
import ffdd.opsconsole.growth.application.H4GrowthApproval;
import ffdd.opsconsole.risk.application.K2OtpInitialApproval;
import ffdd.opsconsole.finance.application.WithdrawalConfigApproval;
import ffdd.opsconsole.market.application.G3MarketApproval;
import ffdd.opsconsole.market.application.G4CatalogApproval;
import ffdd.opsconsole.market.application.G4DividendApproval;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.TeamFundApproval;
import ffdd.opsconsole.treasury.application.TreasuryThresholdApproval;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditReplayDispatcher {
    // Shared by execution and the read model, including supported legacy commands without a guard-table entry.
    private static final Map<String, Set<String>> OPERATIONS = Map.ofEntries(
            Map.entry("A", Set.of("a1_account_create", "a1_account_update_profile", "a1_account_change_role",
                    "a1_account_status_update", "a1_account_reset_2fa", "a1_account_force_logout",
                    "a1_security_baseline_update", "a6_role_grants_update", "a6_role_disable",
                    "a6_role_status_update", "a6_role_delete")),
            Map.entry("C", Set.of("c5_2fa_disable", "c5_password_reset", "c5_user_unlock", "c2_account_freeze",
                    "c2_account_unfreeze", "c2_session_revoke_all", "c5_session_revoke_one",
                    "c2_impersonate_terminate", "c2_impersonate_start", "c3_adjust_approve", "c3_adjust_reject",
                    "c3_adjust_create", "c2_blocklist_upsert", "c2_blocklist_remove")),
            Map.entry("D", Set.of("d_cregis_deposit_policy", "d2_withdraw_approve", "d2_withdraw_unfreeze")),
            Map.entry("E", Set.of("e1_legacy_unknown_zero_credit_cancel", "e1_bundle_discount", "e1_phone_task_policy",
                    "e6_phone_calibration", "e1_sku_create", "e1_sku_update", "e1_sku_status", "e1_sku_delete",
                    "e1_review_create", "e1_review_update", "e1_review_status", "e1_review_delete",
                    "e1_phase_create", "e1_phase_patch", "e1_phase_archive", "e1_gate_create", "e1_gate_patch",
                    "e1_gate_archive", "e1_gate_field", "e1_early_access_update", "e2_phone_tier", "e2_task_create",
                    "e2_task_update", "e2_task_price", "e2_task_status", "e2_task_delete", "e3_config",
                    "e3_config_batch", "e3_tradein", "e3_restore", "e5_device_activate", "e5_device_force_activate",
                    "e5_device_deactivate", "e5_device_unbind", "e5_device_batch_pause", "e5_device_batch_resume",
                    "e4_order_refund", "e4_order_cancel", "e4_order_terminal", "e4_order_state", "e5_datacenter_create",
                    "e5_datacenter_update", "e5_datacenter_delete", "e5_datacenter_pause", "e5_datacenter_resume",
                    "e6_compute_config", "e6_compute_config_batch")),
            Map.entry("F", Set.of("f_vrank_bootstrap", "f_seven_layer_activation", "f_direct_referral_policy", "f_config",
                    "f_ui_config", "f_config_batch", "f_unilevel_rule", "f_commission_status", "f5_commission_reverse",
                    "f5_commission_reissue", "f5_commission_suspension", "f_vrank_override", "f_reward_payout_action",
                    "f4_pool_settle", "f4_leaderboard_period_payout")),
            Map.entry("G", Set.of("g2_exchange_param", "g2_exchange_swap_status", "g2_exchange_cancel_queue",
                    "g3_curve_update", "g3_curve_advance", "g3_curve_control", "g3_override", "g4_genesis_param",
                    "g4_genesis_market_status", "g4_genesis_rerun_dividend", "g7_repurchase_param")),
            Map.entry("H", Set.of("h7_voucher_delete", "h7_voucher_revoke_available", "h3_weekly_region_policy", "promotion_reward_correction", "h1_phase_dial",
                    "h1_phase_control", "h1_phase_override", "h2_trial_cancel", "h2_trial_charge", "h5_checkin_rule",
                    "h8_referral_settlement", "h8_referral_settlement_user")),
            Map.entry("I", Set.of("i3_cap_adjust", "i4_trust_section_manage", "i4_disclosure_publish", "i5_disclosure_publish",
                    "i4_gate_adjust", "i5_gate_adjust", "i5_matrix_configure", "i5_matrix_archive",
                    "i5_jurisdiction_status", "i5_jurisdiction_delete", "i7_course_reward_adjust")),
            Map.entry("J", Set.of("j1_gate_resume", "j1_gate_kill", "j1_batch_kill", "j2_country_manage",
                    "j2_emergency_block", "j3_alert_config", "j4_playbook_execute", "j4_playbook_rollback")),
            Map.entry("K", Set.of("k1_cluster_freeze", "k1_cluster_release", "k1_cluster_cleared", "k1_cluster_flag",
                    "k2_row_flag", "k2_row_blockgift", "k2_row_boardflag", "k2_row_freeze", "k3_rule_create",
                    "k3_rule_toggle", "k3_rule_archive", "k4_user_override", "k4_user_recompute")),
            Map.entry("L", Set.of("l5_task_approve")));
    private final List<AuditReplayable> replayables;
    @Autowired
    private WithdrawalConfigApproval withdrawalConfigApproval;
    @Autowired
    private TreasuryThresholdApproval treasuryThresholdApproval;
    @Autowired
    private E2TaskPricingApproval e2TaskPricingApproval;
    @Autowired
    private TeamFundApproval teamFundApproval;
    @Autowired
    private G3MarketApproval g3MarketApproval;
    @Autowired
    private G4CatalogApproval g4CatalogApproval;
    @Autowired
    private G4DividendApproval g4DividendApproval;
    @Autowired
    private H8RewardParamApproval h8RewardParamApproval;
    @Autowired
    private H5GrowthApproval h5GrowthApproval;
    @Autowired
    private H3GrowthApproval h3GrowthApproval;
    @Autowired
    private H4GrowthApproval h4GrowthApproval;
    @Autowired
    private K2OtpInitialApproval k2OtpInitialApproval;
    @Autowired
    private LearningCourseActivationApproval learningCourseActivationApproval;
    @Autowired
    private A4RegistryApproval a4RegistryApproval;

    private boolean specialized(AuditReplayCommand cmd) {
        return A4RegistryApproval.supports(cmd) || G4CatalogApproval.supports(cmd)
                || LearningCourseActivationApproval.supports(cmd) || H4GrowthApproval.supports(cmd)
                || G4DividendApproval.supports(cmd) || H3GrowthApproval.supports(cmd) || K2OtpInitialApproval.supports(cmd)
                || G3MarketApproval.supports(cmd) || H5GrowthApproval.supports(cmd) || H8RewardParamApproval.supports(cmd)
                || TeamFundApproval.supports(cmd) || E2TaskPricingApproval.supports(cmd)
                || TreasuryThresholdApproval.supports(cmd) || WithdrawalConfigApproval.supports(cmd);
    }

    ApiResult<Void> validateReplayPermission(AuditReplayCommand cmd) {
        if (cmd == null || cmd.op() == null) return ApiResult.fail(422, "COMMAND_REQUIRED");
        if (ffdd.opsconsole.market.application.OpsNexMarketService.isRetiredStakingReplay(cmd.domain(), cmd.op()))
            return ApiResult.fail(422, "RETIRED_FEATURE");
        if (specialized(cmd)) return ApiResult.ok();
        Set<String> operations = cmd.domain() == null ? null : OPERATIONS.get(cmd.domain());
        if (operations == null) return ApiResult.fail(422, "UNKNOWN_REPLAY_DOMAIN:" + cmd.domain());
        if (!operations.contains(cmd.op())) return ApiResult.fail(422, "UNKNOWN_REPLAY_OP:" + cmd.op());
        AuditReplayable target = replayables.stream().filter(candidate -> candidate.domain().equals(cmd.domain()))
                .findFirst().orElse(null);
        return target == null ? ApiResult.fail(422, "UNKNOWN_REPLAY_DOMAIN:" + cmd.domain())
                : target.validateReplayPermission(cmd);
    }

    public ApiResult<?> dispatch(AuditReplayCommand cmd, AuditReplayContext ctx) {
        var permission = validateReplayPermission(cmd);
        if (permission.getCode() != 0) return permission;
        if (A4RegistryApproval.supports(cmd)) {
            return a4RegistryApproval.replay(cmd);
        }
        if (G4CatalogApproval.supports(cmd)) {
            return g4CatalogApproval.replay(cmd, ctx);
        }
        if (LearningCourseActivationApproval.supports(cmd)) {
            return learningCourseActivationApproval.replay(cmd, ctx);
        }
        if (H4GrowthApproval.supports(cmd)) {
            return h4GrowthApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (G4DividendApproval.supports(cmd)) {
            return g4DividendApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (H3GrowthApproval.supports(cmd)) {
            return h3GrowthApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (K2OtpInitialApproval.supports(cmd)) {
            return k2OtpInitialApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (G3MarketApproval.supports(cmd)) {
            return g3MarketApproval.replay(cmd, ctx, A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (H5GrowthApproval.supports(cmd)) {
            return h5GrowthApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (H8RewardParamApproval.supports(cmd)) {
            return h8RewardParamApproval.replay(cmd, ctx,
                    A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (TeamFundApproval.supports(cmd)) {
            return teamFundApproval.replay(cmd, ctx, A2ReplayContext.authenticatedAdminId(), A2ReplayContext.operationId());
        }
        if (E2TaskPricingApproval.supports(cmd)) {
            return e2TaskPricingApproval.replay(cmd, ctx);
        }
        if (TreasuryThresholdApproval.supports(cmd)) {
            return treasuryThresholdApproval.replay(cmd, ctx);
        }
        if (WithdrawalConfigApproval.supports(cmd)) {
            return withdrawalConfigApproval.replay(cmd, ctx);
        }
        AuditReplayable target = replayables.stream()
                .filter(candidate -> candidate.domain().equals(cmd.domain()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return ApiResult.fail(422, "UNKNOWN_REPLAY_DOMAIN:" + cmd.domain());
        }
        return target.replay(cmd, ctx);
    }
}
