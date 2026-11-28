package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.content.application.LearningCourseActivationApproval;
import ffdd.opsconsole.content.application.OpsI18nLearningService;
import ffdd.opsconsole.device.application.E2TaskPricingApproval;
import ffdd.opsconsole.emergency.application.OpsEmergencyControlService;
import ffdd.opsconsole.emergency.application.OpsKillSwitchService;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.application.OpsVietnamPaymentService;
import ffdd.opsconsole.finance.application.PayoutVndConfigService;
import ffdd.opsconsole.finance.application.WithdrawalConfigApproval;
import ffdd.opsconsole.growth.application.H3GrowthApproval;
import ffdd.opsconsole.growth.application.H4GrowthApproval;
import ffdd.opsconsole.growth.application.H5GrowthApproval;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import ffdd.opsconsole.market.application.G2G7ParamApproval;
import ffdd.opsconsole.market.application.G3MarketApproval;
import ffdd.opsconsole.market.application.G4CatalogApproval;
import ffdd.opsconsole.market.application.G4DividendApproval;
import ffdd.opsconsole.market.application.G4ParamApproval;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.risk.application.K1RiskApproval;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.application.DirectReferralService;
import ffdd.opsconsole.team.application.OpsTeamService;
import ffdd.opsconsole.treasury.application.BankRunThresholdPolicy;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.application.TreasuryThresholdApproval;
import java.util.Objects;
import java.util.Set;

/** Only reasons that prove the original review has failed against current business facts. */
final class AuditApprovalRechecks {
    private static final Set<String> H8_SETTLEMENT_REASONS = Set.of("H8_REWARD_SNAPSHOT_CHANGED_REPROPOSE",
            "B1_COVERAGE_BELOW_REDLINE", "REFERRAL_REWARD_DISABLED", "REFERRAL_REWARD_USER_NOT_ELIGIBLE_OR_SETTLED");
    private static final Set<String> F5_STATUS_REASONS = Set.of("F5_COMMISSION_VERSION_CONFLICT",
            "COMMISSION_EVENT_NOT_FOUND", "COMMISSION_USER_SUSPENDED", "F5_COOLDOWN_DEADLINE_UNRECOVERABLE",
            "COVERAGE_BELOW_REDLINE", "F5_COMMISSION_STATE_CONFLICT");

    private AuditApprovalRechecks() {}

    static boolean confirmed(AuditReplayCommand command, BizException failure) {
        if (failure instanceof E2TaskPricingApproval.ConfirmedPricingRecheck) {
            return command != null && "E".equals(command.domain())
                    && ("e2_task_pricing_change".equals(command.op()) || "e2_phone_tier".equals(command.op()));
        }
        if (failure instanceof H3GrowthApproval.ConfirmedRecheckFailure) {
            return H3GrowthApproval.supports(command);
        }
        if (failure instanceof H4GrowthApproval.ConfirmedRecheckFailure) {
            return H4GrowthApproval.supports(command);
        }
        if (failure instanceof OpsTreasuryService.ThresholdVersionAdvanced) {
            return TreasuryThresholdApproval.supports(command) && "B1".equals(TreasuryThresholdApproval.source(command));
        }
        if (failure instanceof BankRunThresholdPolicy.ThresholdVersionAdvanced) {
            return TreasuryThresholdApproval.supports(command) && "B5".equals(TreasuryThresholdApproval.source(command));
        }
        if (failure instanceof PayoutVndConfigService.ConfigVersionAdvanced
                || failure instanceof PayoutVndConfigService.ConfirmedCoverageBreach) {
            return WithdrawalConfigApproval.supports(command) && "d7_payout_vnd_config".equals(command.op());
        }
        if (failure instanceof OpsFinanceService.WithdrawalLimitsVersionAdvanced) {
            return WithdrawalConfigApproval.supports(command) && "D5".equals(WithdrawalConfigApproval.source(command));
        }
        if (failure instanceof OpsVietnamPaymentService.FxQuoteVersionAdvanced) {
            return WithdrawalConfigApproval.supports(command) && "D6".equals(WithdrawalConfigApproval.source(command));
        }
        if (failure instanceof OpsNexMarketService.ConfirmedMarketPolicyRevisionConflict) {
            return G3MarketApproval.supports(command);
        }
        if (failure instanceof RiskReleaseParamsService.ConfirmedSnapshotChange) {
            return K1RiskApproval.supports(command) && "k1_release_param".equals(command.op());
        }
        if (failure instanceof EarningsReleaseService.ConfirmedRecoveryChange) {
            return K1RiskApproval.supports(command) && "k1_earnings_release".equals(command.op());
        }
        if (failure instanceof OpsGrowthService.H5ApprovalRecheckFailure) {
            return H5GrowthApproval.supports(command);
        }
        if (failure instanceof G2G7ParamApproval.OldValueChangedRecheck) {
            return G2G7ParamApproval.supports(command);
        }
        if (failure instanceof OpsEmergencyControlService.ExpiredDrillRecheck) {
            return AuditReplayBusinessPermissionGuard.isJ4Execution(command);
        }
        if (failure instanceof OpsEmergencyControlService.DrillRollbackRecheck
                || failure instanceof OpsKillSwitchService.J4GateOwnershipRecheck) {
            return command != null && "J".equalsIgnoreCase(command.domain())
                    && "j4_playbook_rollback".equalsIgnoreCase(command.op());
        }
        if (failure instanceof DirectReferralService.LockedStatusConflict) {
            return command != null && "F".equalsIgnoreCase(command.domain()) && "f_commission_status".equals(command.op());
        }
        return confirmed(command, failure.getCode(), failure.getMessage());
    }

    static boolean confirmed(AuditReplayCommand command, int code, String reason) {
        if (command == null || command.op() == null || reason == null || (code != 404 && code != 409 && code != 422)) {
            return false;
        }
        if (G2G7ParamApproval.supports(command) || G3MarketApproval.supports(command)) {
            return code == 422 && "COVERAGE_BELOW_REDLINE".equals(reason);
        }
        if (TreasuryThresholdApproval.supports(command)) {
            return "B1".equals(TreasuryThresholdApproval.source(command))
                    && code == 422 && "COVERAGE_BELOW_REDLINE".equals(reason);
        }
        if (G4CatalogApproval.supports(command)) return "G4_CATALOG_SNAPSHOT_CHANGED".equals(reason);
        if (G4DividendApproval.supports(command)) {
            return (code == 409 && "G4_DIVIDEND_STALE".equals(reason))
                    || (code == 422 && "COVERAGE_BELOW_REDLINE".equals(reason));
        }
        if (G4ParamApproval.supports(command)) {
            return (code == 409 && "G4_GENESIS_PARAM_STALE".equals(reason))
                    || (code == 422 && "COVERAGE_BELOW_REDLINE".equals(reason));
        }
        if (A4RegistryApproval.supports(command)) return "A4_SCHEMA_VERSION_STALE".equals(reason);
        if (LearningCourseActivationApproval.supports(command)) {
            return Set.of("LEARNING_ACTIVATION_SNAPSHOT_CHANGED", "COVERAGE_BELOW_REDLINE").contains(reason);
        }
        if (OpsI18nLearningService.isCourseRewardAdjustment(command)) {
            return "LEARNING_COURSE_REWARD_SNAPSHOT_CHANGED".equals(reason);
        }
        return switch (command.op()) {
            case "e2_task_create", "e2_task_update" -> "E".equals(command.domain()) && command.params()!=null
                    && "PHONE".equals(command.params().get("poolCode"))
                    && Set.of("PHONE_TASK_REWARD_FIELDS_FORBIDDEN","SIMULATED_DURATION_SECONDS_INVALID",
                        "SIMULATED_TASK_POLICY_VERSION_CONFLICT").contains(reason);
            case "h2_trial_cancel" -> code == 409 && "H".equals(command.domain())
                    && "TRIAL_SESSION_ALREADY_TERMINAL".equals(reason);
            case "h8_referral_settlement", "h8_referral_settlement_user" -> H8_SETTLEMENT_REASONS.contains(reason);
            case "h8_reward_param_change" -> Set.of("H8_CONFIG_VERSION_CONFLICT", "H8_PARAM_APPROVAL_NOT_REQUIRED",
                    "B1_COVERAGE_BELOW_REDLINE").contains(reason);
            case "c3_adjust_approve" -> Set.of("ASSET_ADJUSTMENT_NOT_FOUND", "ASSET_ADJUSTMENT_NOT_REVIEWABLE",
                    "COVERAGE_BELOW_REDLINE", "C3_INSUFFICIENT_BALANCE").contains(reason);
            case "f_commission_status" -> F5_STATUS_REASONS.contains(reason) || confirmedF5Unfreeze(command, reason);
            case "f5_commission_reverse" -> Set.of("COMMISSION_EVENT_NOT_FOUND", "COMMISSION_REVERSE_STATE_CONFLICT",
                    "REFUND_REF_NOT_FOUND").contains(reason);
            case "f5_commission_reissue" -> "COVERAGE_BELOW_REDLINE".equals(reason)
                    || "DIRECT_REFERRAL_REISSUE_REQUIRES_SOURCE_RECONCILIATION".equals(reason)
                    || reason.matches("COMMISSION_REISSUE_(SOURCE_NOT_FOUND|STATE_CONFLICT|ALREADY_CONSUMED):CM-[0-9]+");
            case "f5_commission_suspension" -> Set.of("COMMISSION_USER_KINDS_ALREADY_SUSPENDED",
                    "COMMISSION_USER_KINDS_NOT_SUSPENDED").contains(reason);
            case "i4_disclosure_publish", "i5_disclosure_publish" -> Set.of("A2_DISCLOSURE_SNAPSHOT_CHANGED",
                    "DISCLOSURE_DRAFT_VERSION_NOT_FOUND", "DISCLOSURE_DRAFT_SNAPSHOT_CHANGED",
                    "DISCLOSURE_DRAFT_CHANGED_SAVE_BEFORE_PUBLISH").contains(reason);
            case "j4_playbook_execute" -> code == 409 && "J".equals(command.domain())
                    && AuditReplayBusinessPermissionGuard.J4_PLAYBOOK_SNAPSHOT_CHANGED.equals(reason);
            default -> false;
        };
    }

    private static boolean confirmedF5Unfreeze(AuditReplayCommand command, String reason) {
        return "DIRECT_REFERRAL_SPONSOR_FROZEN".equals(reason) && command.params() != null
                && "COOLING".equals(OpsTeamService.canonicalCommissionState(Objects.toString(command.params().get("value"), "")));
    }
}
