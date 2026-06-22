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
                api("Architecture", "/api/admin/platform/architecture", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE", false),
                api("A2Audit", "/api/admin/platform/audit", "PERM_AUDIT_READ", "PERM_AUDIT_EXPORT", false),
                api("A3Config", "/api/admin/platform/config", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE", false),
                api("A4EventCenter", "/api/admin/platform/events", "PERM_AUDIT_READ", "PERM_AUDIT_EXPORT", false),
                api("AdminCommand", "/api/admin/commands", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE", false),
                api("DynamicOptions", "/api/admin/options/{domain}/{name}", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE", false),
                api("GlobalSearch", "/api/admin/platform/search", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE", false),
                api("RuntimeContracts", "/api/admin/platform/runtime/contracts", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE", false),
                api("MediaUpload", "/api/admin/media/uploads", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE", false)));
        map.put(DomainCode.B, List.of(
                api("ReserveCoverage", "/api/admin/treasury/overview", "PERM_TREASURY_READ", "PERM_TREASURY_WRITE", true),
                api("DualLedger", "/api/admin/treasury/dual-ledger", "PERM_TREASURY_READ", "PERM_TREASURY_WRITE", true),
                api("TreasuryInjection", "/api/admin/treasury/injections", "PERM_TREASURY_READ", "PERM_TREASURY_WRITE", true)));
        map.put(DomainCode.C, List.of(
                api("UserProfile", "/api/admin/users/profiles", "PERM_USER_READ", "PERM_USER_WRITE", false),
                api("UserSession", "/api/admin/users/sessions", "PERM_USER_READ", "PERM_USER_WRITE", false),
                api("ManualAssetAdjustment", "/api/admin/users/profiles/{userId}/asset-adjustments", "PERM_USER_READ", "PERM_USER_WRITE", false)));
        map.put(DomainCode.D, List.of(
                api("TopupReconciliation", "/api/admin/finance/topup/overview", "PERM_WITHDRAWAL_READ", "PERM_WITHDRAWAL_REVIEW", true),
                api("TopupFlows", "/api/admin/finance/topup/flows", "PERM_WITHDRAWAL_READ", "PERM_WITHDRAWAL_REVIEW", false),
                api("Withdrawal", "/api/admin/finance/withdrawals", "PERM_WITHDRAWAL_READ", "PERM_WITHDRAWAL_REVIEW", false),
                api("WithdrawalParam", "/api/admin/finance/withdrawal-params", "PERM_WITHDRAWAL_READ", "PERM_WITHDRAWAL_REVIEW", false)));
        map.put(DomainCode.E, List.of(
                api("Device", "/api/admin/devices", "PERM_DEVICE_READ", "PERM_DEVICE_WRITE", false),
                api("DeviceSku", "/api/admin/devices/skus", "PERM_DEVICE_READ", "PERM_DEVICE_WRITE", false),
                api("DeviceReview", "/api/admin/devices/reviews", "PERM_DEVICE_READ", "PERM_DEVICE_WRITE", false),
                api("DeviceTask", "/api/admin/devices/tasks", "PERM_DEVICE_READ", "PERM_DEVICE_WRITE", false),
                api("DeviceOrder", "/api/admin/devices/orders", "PERM_DEVICE_READ", "PERM_DEVICE_WRITE", false),
                api("E3Config", "/api/admin/devices/e3/config", "PERM_DEVICE_READ", "PERM_DEVICE_WRITE", false),
                api("E3TradeinTx", "/api/admin/devices/e3/tradein", "PERM_DEVICE_READ", "PERM_DEVICE_WRITE", true),
                api("DeviceRestore", "/api/admin/devices/{deviceId}/restore", "PERM_DEVICE_READ", "PERM_DEVICE_RESTORE", false)));
        map.put(DomainCode.F, List.of(
                api("TeamOverview", "/api/admin/teams/overview", "PERM_TEAM_READ", "PERM_TEAM_WRITE", false),
                api("TeamCommission", "/api/admin/teams/commissions", "PERM_TEAM_READ", "PERM_TEAM_WRITE", false),
                api("TeamRanks", "/api/admin/teams/ranks", "PERM_TEAM_READ", "PERM_TEAM_WRITE", false)));
        map.put(DomainCode.G, List.of(
                api("StakingPools", "/api/admin/market/staking", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true),
                api("StakingPoolParam", "/api/admin/market/staking/pools/{tierKey}/params/{paramKey}", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true),
                api("StakingPoolSaleStatus", "/api/admin/market/staking/pools/{tierKey}/sale-status", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true),
                api("StakingPoolKillStatus", "/api/admin/market/staking/pools/{tierKey}/kill-status", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true),
                api("NexMarketCurve", "/api/admin/market/nex/curve", "PERM_MARKET_READ", "PERM_MARKET_WRITE", false),
                api("NexMarketAdvance", "/api/admin/market/nex/curve/advance", "PERM_MARKET_READ", "PERM_MARKET_WRITE", false),
                api("GenesisEconomy", "/api/admin/market/nex/genesis", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true),
                api("GenesisParam", "/api/admin/market/nex/genesis/params/{paramKey}", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true),
                api("GenesisMarketStatus", "/api/admin/market/nex/genesis/market-status", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true),
                api("GenesisDividendBatch", "/api/admin/market/nex/genesis/dividend-batches/{batchNo}/rerun", "PERM_MARKET_READ", "PERM_MARKET_WRITE", false),
                api("RepurchaseProduct", "/api/admin/market/nex/repurchase", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true),
                api("RepurchaseParam", "/api/admin/market/nex/repurchase/params/{paramKey}", "PERM_MARKET_READ", "PERM_MARKET_WRITE", true)));
        map.put(DomainCode.H, List.of(
                api("GrowthPhase", "/api/admin/growth/phases", "PERM_GROWTH_READ", "PERM_GROWTH_WRITE", false),
                api("CheckInNexReward", "/api/admin/growth/check-in", "PERM_GROWTH_READ", "PERM_GROWTH_WRITE", false),
                api("WithdrawNexGate", "/api/admin/growth/withdraw-gate", "PERM_GROWTH_READ", "PERM_GROWTH_WRITE", false)));
        map.put(DomainCode.I, List.of(
                api("Conversation", "/api/admin/content/conversations", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("ConversationTransfer", "/api/admin/content/conversations/{conversationNo}/transfer", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("TransferAcceptReturn", "/api/admin/content/conversations/{conversationNo}/transfer/{action}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("SupportTicket", "/api/admin/content/tickets", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("SupportKnowledge", "/api/admin/content/knowledge/overview", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("SupportFaqCrud", "/api/admin/content/knowledge/faqs", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("SupportSlaRule", "/api/admin/content/knowledge/sla/{category}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("NovaOverview", "/api/admin/content/nova/overview", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("NovaChannelCrud", "/api/admin/content/nova/channels", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("NovaTemplateStatus", "/api/admin/content/nova/templates/{channel}/status", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("NovaSocialDistribution", "/api/admin/content/nova/social-distribution", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("NovaSocialPool", "/api/admin/content/nova/social-pools/{poolKey}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("CopyAbOverview", "/api/admin/content/copy-ab/overview", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("CopyVersion", "/api/admin/content/copy-ab/copies/{copyKey}/versions", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("CopyExperiment", "/api/admin/content/copy-ab/experiments/{experimentId}/{action}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("NotificationCampaign", "/api/admin/content/campaigns/overview", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("NotificationCampaignAction", "/api/admin/content/campaigns/{campaignNo}/{action}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("NotificationCapRule", "/api/admin/content/campaigns/caps/{tier}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("TrustDisclosureOverview", "/api/admin/content/trust-disclosure/overview", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("TrustSection", "/api/admin/content/trust-disclosure/trust-sections/{sectionKey}/{action}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("DisclosureJurisdiction", "/api/admin/content/trust-disclosure/disclosures/{jurisdiction}/{action}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("DisclosureGateAction", "/api/admin/content/trust-disclosure/disclosures/gated-actions", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("I18nLearningOverview", "/api/admin/content/i18n-learning/overview", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("I18nMessage", "/api/admin/content/i18n-learning/messages/{messageKey}/{action}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false),
                api("LearningCourse", "/api/admin/content/i18n-learning/courses/{courseId}", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", true),
                api("LearningFeaturedCourse", "/api/admin/content/i18n-learning/courses/featured", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE", false)));
        map.put(DomainCode.J, List.of(
                api("KillSwitch", "/api/admin/emergency/kill-switches", "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE", false),
                api("KillSwitchUpdate", "/api/admin/emergency/kill-switches/{key}", "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE", false),
                api("EmergencyDisable", "/api/admin/emergency/kill-switches/emergency-disable", "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE", false)));
        map.put(DomainCode.K, List.of(
                api("RiskCase", "/api/admin/risk/cases", "PERM_RISK_READ", "PERM_RISK_WRITE", false),
                api("RiskCaseDecision", "/api/admin/risk/cases/{caseNo}/decision", "PERM_RISK_READ", "PERM_RISK_WRITE", false),
                api("RiskSignal", "/api/admin/risk/signals", "PERM_RISK_READ", "PERM_RISK_WRITE", false),
                api("ArbitrageSignal", "/api/admin/risk/arbitrage/overview", "PERM_RISK_READ", "PERM_RISK_WRITE", false),
                api("ArbitrageAction", "/api/admin/risk/arbitrage/rows/{rowId}/{action}", "PERM_RISK_READ", "PERM_RISK_WRITE", false),
                api("RiskScoringModel", "/api/admin/risk/scoring/overview", "PERM_RISK_READ", "PERM_RISK_WRITE", false),
                api("RiskScoringAction", "/api/admin/risk/scoring/**", "PERM_RISK_READ", "PERM_RISK_WRITE", false),
                api("WithdrawRule", "/api/admin/risk/withdraw-rules", "PERM_RISK_READ", "PERM_RISK_WRITE", true),
                api("WithdrawRuleDryRun", "/api/admin/risk/withdraw-rules/dry-runs", "PERM_RISK_READ", "PERM_RISK_WRITE", false)));
        map.put(DomainCode.L, List.of(
                api("BIReport", "/api/admin/bi/reports", "PERM_BI_READ", "PERM_BI_EXPORT", false),
                api("BIReportAction", "/api/admin/bi/reports/{reportId}/{action}", "PERM_BI_READ", "PERM_BI_EXPORT", false),
                api("RegulatoryTemplates", "/api/admin/bi/regulatory/templates", "PERM_BI_READ", "PERM_BI_EXPORT", false)));
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
