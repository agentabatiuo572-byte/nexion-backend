package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.domain.DomainCode;
import ffdd.opsconsole.common.domain.OpsDomainCatalog;
import ffdd.opsconsole.common.domain.OpsDomainDescriptor;
import ffdd.opsconsole.platform.dto.ApiFamilyContract;
import ffdd.opsconsole.platform.dto.OpsDomainCommandValidationRequest;
import ffdd.opsconsole.platform.dto.OpsDomainCommandValidationResponse;
import ffdd.opsconsole.platform.dto.OpsDomainRuntimeContract;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsDomainRuntimeService {
    private static final double DEFAULT_B1_COVERAGE_REDLINE = 1.05D;
    private static final List<String> REQUIRED_WRITE_HEADERS = List.of(OpsAdminApi.IDEMPOTENCY_KEY_HEADER, OpsAdminApi.REASON_FIELD);
    private static final List<String> SUNSET_CAPABILITIES = OpsDomainCatalog.deprecatedCapabilities();
    private static final Map<DomainCode, List<ApiFamilyContract>> API_FAMILIES = apiFamilies();
    private static final Map<DomainCode, List<String>> REDLINES = redlines();
    private static final Map<DomainCode, List<String>> UPDATE_CORRECTIONS = updateCorrections();
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = allowedTransitions();

    public ApiResult<List<OpsDomainRuntimeContract>> contracts() {
        return ApiResult.ok(OpsDomainCatalog.activeDomains().stream()
                .map(this::contractFor)
                .toList());
    }

    public ApiResult<OpsDomainRuntimeContract> contract(String adminResource) {
        Optional<OpsDomainDescriptor> domain = domainByResource(adminResource);
        if (domain.isEmpty()) {
            return ApiResult.fail(404, "DOMAIN_NOT_FOUND");
        }
        return ApiResult.ok(contractFor(domain.get()));
    }

    public ApiResult<OpsDomainCommandValidationResponse> validateCommand(
            String adminResource,
            String idempotencyKey,
            OpsDomainCommandValidationRequest request) {
        Optional<OpsDomainDescriptor> domain = domainByResource(adminResource);
        if (domain.isEmpty()) {
            return ApiResult.fail(404, "DOMAIN_NOT_FOUND");
        }
        if (request == null || !StringUtils.hasText(request.command())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COMMAND_REQUIRED");
        }

        DomainCode code = domain.get().code();
        String command = request.command().trim().toUpperCase(Locale.ROOT);
        if (containsSunsetTerm(command) || containsSunsetTerm(request.resource())) {
            return ApiResult.fail(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        boolean writeCommand = isWriteCommand(command);
        if (writeCommand && !StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (writeCommand && !StringUtils.hasText(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (code == DomainCode.B && belowCoverageRedline(request.coverageRatio(), request.coverageRedline())) {
            return ApiResult.fail(
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        if (code == DomainCode.H && command.contains("PHASE_PARAM")) {
            return ApiResult.fail(
                    OpsErrorCode.PHASE_PARAM_READONLY.httpStatus(),
                    OpsErrorCode.PHASE_PARAM_READONLY.name());
        }
        if (!isAllowedTransition(code, request.resource(), command, request.fromStatus(), request.toStatus())) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }

        List<String> rules = new ArrayList<>();
        if (writeCommand) {
            rules.add("Idempotency-Key");
            rules.add("Confirm-with-Reason");
            rules.add("A2 audit");
        }
        if (code == DomainCode.B) {
            rules.add("B1 coverage redline >= " + DEFAULT_B1_COVERAGE_REDLINE);
        }
        return ApiResult.ok(new OpsDomainCommandValidationResponse(
                true,
                code.name(),
                command,
                List.copyOf(rules),
                writeCommand ? code.name() + "_OPS_COMMAND_ACCEPTED" : null));
    }

    private OpsDomainRuntimeContract contractFor(OpsDomainDescriptor descriptor) {
        DomainCode code = descriptor.code();
        return new OpsDomainRuntimeContract(
                code.name(),
                code.displayName(),
                descriptor.adminApiPrefix(),
                descriptor.packageName(),
                descriptor.activeCapabilities(),
                API_FAMILIES.getOrDefault(code, List.of()),
                REQUIRED_WRITE_HEADERS,
                true,
                REDLINES.getOrDefault(code, List.of()),
                UPDATE_CORRECTIONS.getOrDefault(code, List.of()),
                SUNSET_CAPABILITIES,
                "MIGRATING_TO_MONOLITH");
    }

    private Optional<OpsDomainDescriptor> domainByResource(String adminResource) {
        String normalized = adminResource == null ? "" : adminResource.trim().toLowerCase(Locale.ROOT);
        return OpsDomainCatalog.activeDomains().stream()
                .filter(domain -> domain.code().adminResource().equals(normalized))
                .findFirst();
    }

    private boolean isWriteCommand(String command) {
        return !(command.startsWith("READ_")
                || command.startsWith("LIST_")
                || command.startsWith("QUERY_")
                || command.startsWith("EXPORT_PREVIEW"));
    }

    private boolean belowCoverageRedline(Double coverageRatio, Double coverageRedline) {
        if (coverageRatio == null) {
            return false;
        }
        double redline = coverageRedline == null ? DEFAULT_B1_COVERAGE_REDLINE : coverageRedline;
        return coverageRatio < redline;
    }

    private boolean isAllowedTransition(
            DomainCode domainCode,
            String resource,
            String command,
            String fromStatus,
            String toStatus) {
        if (!StringUtils.hasText(fromStatus) || !StringUtils.hasText(toStatus)) {
            return true;
        }
        String normalizedFrom = fromStatus.trim().toUpperCase(Locale.ROOT);
        String normalizedTo = toStatus.trim().toUpperCase(Locale.ROOT);
        if (domainCode == DomainCode.E && "RESTORE_DEVICE".equals(command)) {
            return "RECYCLED".equals(normalizedFrom) && "OFFLINE".equals(normalizedTo);
        }
        String transitionKey = domainCode.name() + ":" + normalizeResource(resource);
        Set<String> allowed = ALLOWED_TRANSITIONS.get(transitionKey);
        return allowed == null || allowed.contains(normalizedFrom + "->" + normalizedTo);
    }

    private String normalizeResource(String resource) {
        if (!StringUtils.hasText(resource)) {
            return "*";
        }
        return resource.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }

    private boolean containsSunsetTerm(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("premium") || normalized.contains("nexv2") || normalized.contains("nex.v2")
                || normalized.contains("points");
    }

    private static Map<DomainCode, List<ApiFamilyContract>> apiFamilies() {
        Map<DomainCode, List<ApiFamilyContract>> map = new EnumMap<>(DomainCode.class);
        map.put(DomainCode.A, List.of(
                api("Architecture", "/api/admin/platform/architecture", "platform_a1_read", "platform_a1_write", false),
                api("A2Audit", "/api/admin/platform/audit", "platform_a2_read", "platform_a2_write", false),
                api("A3Config", "/api/admin/platform/config", "platform_a3_read", "platform_a3_write", false),
                api("A4EventCenter", "/api/admin/platform/events", "platform_a4_read", "platform_a4_write", false),
                api("AdminCommand", "/api/admin/commands", "platform_a1_read", "platform_a1_write", false),
                api("DynamicOptions", "/api/admin/options/{domain}/{name}", "platform_a1_read", "platform_a1_write", false),
                api("GlobalSearch", "/api/admin/platform/search", "platform_a1_read", "platform_a1_write", false),
                api("RuntimeContracts", "/api/admin/platform/runtime/contracts", "platform_a1_read", "platform_a1_write", false),
                api("MediaUpload", "/api/admin/media/uploads", "platform_a1_read", "platform_a1_write", false)));
        map.put(DomainCode.B, List.of(
                api("ReserveCoverage", "/api/admin/treasury/overview", "finance_d3_read", "finance_d3_write", true),
                api("DualLedger", "/api/admin/treasury/dual-ledger", "finance_d3_read", "finance_d3_write", true),
                api("TreasuryInjection", "/api/admin/treasury/injections", "finance_d3_read", "finance_d3_write", true)));
        map.put(DomainCode.C, List.of(
                api("UserProfile", "/api/admin/users/profiles", "user_c1_read", "user_c1_write", false),
                api("UserSession", "/api/admin/users/sessions", "user_c5_read", "user_c5_write", false),
                api("ManualAssetAdjustment", "/api/admin/users/profiles/{userId}/asset-adjustments", "user_c3_read", "user_c3_adjust_create", false)));
        map.put(DomainCode.D, List.of(
                api("TopupReconciliation", "/api/admin/finance/topup/overview", "finance_d1_read", "finance_d1_write", true),
                api("TopupFlows", "/api/admin/finance/topup/flows", "finance_d1_read", "finance_d1_write", false),
                api("Withdrawal", "/api/admin/finance/withdrawals", "finance_d2_read", "finance_d2_withdrawal_approve", false),
                api("WithdrawalParam", "/api/admin/finance/withdrawal-params", "finance_d5_read", "finance_d5_daily_limit_write", false)));
        map.put(DomainCode.E, List.of(
                api("Device", "/api/admin/devices", "device_e1_read", "device_e1_write", false),
                api("DeviceSku", "/api/admin/devices/skus", "device_e1_read", "device_e1_write", false),
                api("DeviceReview", "/api/admin/devices/reviews", "device_e1_read", "device_e1_write", false),
                api("DeviceTask", "/api/admin/devices/tasks", "device_e2_read", "device_e2_write", false),
                api("DeviceOrder", "/api/admin/devices/orders", "device_e4_read", "device_e4_write", false),
                api("E3Config", "/api/admin/devices/e3/config", "device_e3_read", "device_e3_write", false),
                api("E3TradeinTx", "/api/admin/devices/e3/tradein", "device_e3_read", "device_e3_write", true),
                api("DeviceRestore", "/api/admin/devices/{deviceId}/restore", "device_e5_read", "device_e5_write", false)));
        map.put(DomainCode.F, List.of(
                api("TeamOverview", "/api/admin/teams/overview", "network_f1_read", "network_f1_write", false),
                api("TeamCommission", "/api/admin/teams/commissions", "network_f5_read", "network_f5_write", false),
                api("TeamRanks", "/api/admin/teams/ranks", "network_f1_read", "network_f1_write", false)));
        map.put(DomainCode.G, List.of(
                api("StakingPools", "/api/admin/market/staking", "finprod_g1_read", "finprod_g1_write", true),
                api("StakingPoolParam", "/api/admin/market/staking/pools/{tierKey}/params/{paramKey}", "finprod_g1_read", "finprod_g1_write", true),
                api("StakingPoolSaleStatus", "/api/admin/market/staking/pools/{tierKey}/sale-status", "finprod_g1_read", "finprod_g1_write", true),
                api("StakingPoolKillStatus", "/api/admin/market/staking/pools/{tierKey}/kill-status", "finprod_g1_read", "finprod_g1_kill_toggle", true),
                api("NexMarketCurve", "/api/admin/market/nex/curve", "finprod_g3_read", "finprod_g3_write", false),
                api("NexMarketAdvance", "/api/admin/market/nex/curve/advance", "finprod_g3_read", "finprod_g3_write", false),
                api("GenesisEconomy", "/api/admin/market/nex/genesis", "finprod_g4_read", "finprod_g4_write", true),
                api("GenesisParam", "/api/admin/market/nex/genesis/params/{paramKey}", "finprod_g4_read", "finprod_g4_write", true),
                api("GenesisMarketStatus", "/api/admin/market/nex/genesis/market-status", "finprod_g4_read", "finprod_g4_market_toggle", true),
                api("GenesisDividendBatch", "/api/admin/market/nex/genesis/dividend-batches/{batchNo}/rerun", "finprod_g4_read", "finprod_g4_write", false),
                api("RepurchaseProduct", "/api/admin/market/nex/repurchase", "finprod_g7_read", "finprod_g7_write", true),
                api("RepurchaseParam", "/api/admin/market/nex/repurchase/params/{paramKey}", "finprod_g7_read", "finprod_g7_write", true)));
        map.put(DomainCode.H, List.of(
                api("GrowthPhase", "/api/admin/growth/phases", "growth_h1_read", "growth_h1_write", false),
                api("CheckInNexReward", "/api/admin/growth/check-in", "growth_h5_read", "growth_h5_write", false),
                api("WithdrawNexGate", "/api/admin/growth/withdraw-gate", "growth_h5_read", "growth_h5_write", false)));
        map.put(DomainCode.I, List.of(
                api("Conversation", "/api/admin/content/conversations", "service_m3_read", "service_m3_write", false),
                api("ConversationTransfer", "/api/admin/content/conversations/{conversationNo}/transfer", "service_m3_read", "service_m3_write", false),
                api("TransferAcceptReturn", "/api/admin/content/conversations/{conversationNo}/transfer/{action}", "service_m3_read", "service_m3_write", false),
                api("SupportTicket", "/api/admin/content/tickets", "service_m2_read", "service_m2_write", false),
                api("SupportKnowledge", "/api/admin/content/knowledge/overview", "service_m4_read", "service_m4_write", false),
                api("SupportFaqCrud", "/api/admin/content/knowledge/faqs", "service_m4_read", "service_m4_write", false),
                api("SupportSlaRule", "/api/admin/content/knowledge/sla/{category}", "service_m4_read", "service_m4_write", false),
                api("NovaOverview", "/api/admin/content/nova/overview", "content_i2_read", "content_i2_write", false),
                api("NovaChannelCrud", "/api/admin/content/nova/channels", "content_i2_read", "content_i2_write", false),
                api("NovaTemplateStatus", "/api/admin/content/nova/templates/{channel}/status", "content_i2_read", "content_i2_write", false),
                api("NovaSocialDistribution", "/api/admin/content/nova/social-distribution", "content_i2_read", "content_i2_write", false),
                api("NovaSocialPool", "/api/admin/content/nova/social-pools/{poolKey}", "content_i2_read", "content_i2_write", false),
                api("CopyAbOverview", "/api/admin/content/copy-ab/overview", "content_i1_read", "content_i1_write", false),
                api("CopyCreate", "/api/admin/content/copy-ab/copies", "content_i1_read", "content_i1_copy_create", false),
                api("CopyVersion", "/api/admin/content/copy-ab/copies/{copyKey}/versions", "content_i1_read", "content_i1_write", false),
                api("CopyExperiment", "/api/admin/content/copy-ab/experiments/{experimentId}/{action}", "content_i1_read", "content_i1_experiment_manage", false),
                api("NotificationCampaign", "/api/admin/content/campaigns/overview", "content_i3_read", "content_i3_write", false),
                api("NotificationCampaignAction", "/api/admin/content/campaigns/{campaignNo}/{action}", "content_i3_read", "content_i3_write", false),
                api("NotificationCapRule", "/api/admin/content/campaigns/caps/{tier}", "content_i3_read", "content_i3_cap_adjust", false),
                api("TrustCenterOverview", "/api/admin/content/trust-disclosure/overview", "content_i4_read", "content_i4_write", false),
                api("DisclosureOverview", "/api/admin/content/trust-disclosure/overview", "content_i5_read", "content_i5_write", false),
                api("TrustSectionDraft", "/api/admin/content/trust-disclosure/trust-sections/{sectionKey}/versions/{version}", "content_i4_read", "content_i4_write", false),
                api("TrustSectionStandardPublish", "/api/admin/content/trust-disclosure/trust-sections/{sectionKey}/{action}", "content_i4_read", "content_i4_publish_standard", false),
                api("TrustSectionSensitivePublish", "/api/admin/content/trust-disclosure/trust-sections/{sectionKey}/{action}", "content_i4_read", "content_i4_trust_section_manage", false),
                api("DisclosureJurisdiction", "/api/admin/content/trust-disclosure/disclosures/{jurisdiction}/{action}", "content_i5_read", "content_i5_disclosure_publish", false),
                api("DisclosureGateAction", "/api/admin/content/trust-disclosure/disclosures/gated-actions", "content_i5_read", "content_i5_gate_adjust", false),
                api("I18nLearningOverview", "/api/admin/content/i18n-learning/overview", "content_i6_read", "content_i6_write", false),
                api("I18nMessage", "/api/admin/content/i18n-learning/messages/{messageKey}/{action}", "content_i6_read", "content_i6_write", false),
                api("LearningCourse", "/api/admin/content/i18n-learning/courses/{courseId}", "content_i7_read", "content_i7_write", true),
                api("LearningFeaturedCourse", "/api/admin/content/i18n-learning/courses/featured", "content_i7_read", "content_i7_write", false)));
        map.put(DomainCode.J, List.of(
                api("KillSwitch", "/api/admin/emergency/kill-switches", "emergency_j1_read", "emergency_j1_write", false),
                api("KillSwitchKill", "/api/admin/emergency/kill-switches/{key}", "emergency_j1_read", "emergency_j1_gate_kill", false),
                api("KillSwitchResume", "/api/admin/emergency/kill-switches/{key}", "emergency_j1_read", "emergency_j1_gate_resume", false),
                api("EmergencyDisable", "/api/admin/emergency/kill-switches/emergency-disable", "emergency_j1_read", "emergency_j1_batch_kill", false)));
        map.put(DomainCode.K, List.of(
                api("RiskCase", "/api/admin/risk/cases", "risk_k1_read", "risk_k1_write", false),
                api("RiskCaseDecision", "/api/admin/risk/cases/{caseNo}/decision", "risk_k1_read", "risk_k1_write", false),
                api("RiskSignal", "/api/admin/risk/signals", "risk_k1_read", "risk_k1_write", false),
                api("ArbitrageSignal", "/api/admin/risk/arbitrage/overview", "risk_k2_read", "risk_k2_write", false),
                api("ArbitrageAction", "/api/admin/risk/arbitrage/rows/{rowId}/{action}", "risk_k2_read", "risk_k2_write", false),
                api("RiskScoringModel", "/api/admin/risk/scoring/overview", "risk_k4_read", "risk_k4_write", false),
                api("RiskScoringAction", "/api/admin/risk/scoring/**", "risk_k4_read", "risk_k4_write", false),
                api("WithdrawRule", "/api/admin/risk/withdraw-rules", "risk_k3_read", "risk_k3_write", true),
                api("WithdrawRuleDryRun", "/api/admin/risk/withdraw-rules/dry-runs", "risk_k3_read", "risk_k3_write", false)));
        map.put(DomainCode.L, List.of(
                api("BIReport", "/api/admin/bi/reports", "bi_l5_read", "bi_l5_write", false),
                api("BIReportAction", "/api/admin/bi/reports/{reportId}/{action}", "bi_l5_read", "bi_l5_write", false),
                api("RegulatoryTemplates", "/api/admin/bi/regulatory/templates", "bi_l5_read", "bi_l5_write", false)));
        return Map.copyOf(map);
    }

    private static ApiFamilyContract api(
            String resource,
            String path,
            String readPermission,
            String writePermission,
            boolean b1RedlineTriggered) {
        return new ApiFamilyContract(
                resource,
                path,
                readPermission,
                writePermission,
                true,
                true,
                true,
                b1RedlineTriggered,
                b1RedlineTriggered
                        ? List.of("IDEMPOTENCY_KEY_REQUIRED", "REASON_REQUIRED", "COVERAGE_BELOW_REDLINE")
                        : List.of("IDEMPOTENCY_KEY_REQUIRED", "REASON_REQUIRED", "INVALID_STATE_TRANSITION"));
    }

    private static Map<DomainCode, List<String>> redlines() {
        Map<DomainCode, List<String>> map = new EnumMap<>(DomainCode.class);
        map.put(DomainCode.B, List.of("B1 coverageRatio must stay >= 1.05; violation returns 422 COVERAGE_BELOW_REDLINE"));
        map.put(DomainCode.D, List.of("Withdrawal state transitions outside the approved chain return 409 INVALID_STATE_TRANSITION"));
        map.put(DomainCode.E, List.of("Recycled device restore can only move RECYCLED -> OFFLINE"));
        map.put(DomainCode.H, List.of("Growth phase core parameters are readonly after launch"));
        map.put(DomainCode.J, List.of("Kill switch writes require reason and audit; sunset gates cannot be reintroduced"));
        return Map.copyOf(map);
    }

    private static Map<DomainCode, List<String>> updateCorrections() {
        Map<DomainCode, List<String>> map = new EnumMap<>(DomainCode.class);
        map.put(DomainCode.D, List.of("D5_H1_WITHDRAW_NEX_GATE"));
        map.put(DomainCode.E, List.of("E3B_DEVICE_RESTORE"));
        map.put(DomainCode.G, List.of("G3_WEEKLY_CURVE"));
        map.put(DomainCode.H, List.of("D5_H1_WITHDRAW_NEX_GATE", "H5_CHECKIN_NEX_REWARD"));
        map.put(DomainCode.I, List.of("I9_CROSS_AGENT_TRANSFER"));
        map.put(DomainCode.J, List.of("J1_GATE_SHRINK"));
        return Map.copyOf(map);
    }

    private static Map<String, Set<String>> allowedTransitions() {
        return Map.of(
                "D:WITHDRAWAL", Set.of(
                        "PENDING_REVIEW->APPROVED",
                        "PENDING_REVIEW->REJECTED",
                        "APPROVED->PENDING_CHAIN",
                        "PENDING_CHAIN->CHAIN_SUBMITTED",
                        "CHAIN_SUBMITTED->SUCCEEDED",
                        "CHAIN_SUBMITTED->FAILED"),
                "E:DEVICE", Set.of(
                        "OFFLINE->ACTIVE",
                        "ACTIVE->SUSPENDED",
                        "SUSPENDED->ACTIVE",
                        "ACTIVE->RECYCLED",
                        "RECYCLED->OFFLINE"),
                "I:CONVERSATION", Set.of(
                        "OPEN->TRANSFERRED",
                        "OPEN->RESOLVED",
                        "TRANSFERRED->OPEN",
                        "TRANSFERRED->RESOLVED"));
    }
}
