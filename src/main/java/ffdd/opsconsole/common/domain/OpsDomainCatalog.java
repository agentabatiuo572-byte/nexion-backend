package ffdd.opsconsole.common.domain;

import ffdd.opsconsole.common.api.OpsAdminApi;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class OpsDomainCatalog {
    private static final List<OpsDomainDescriptor> ACTIVE_DOMAINS = List.of(
            descriptor(DomainCode.A, "Admin", "RBAC", "Audit", "SystemConfig", "Idempotency"),
            descriptor(DomainCode.B, "TreasuryLedger", "CoverageRedline", "ReserveCockpit", "RiskRadar"),
            descriptor(DomainCode.C, "UserProfile", "KycReview", "AccountSecurity", "ManualAssetAdjustment"),
            descriptor(DomainCode.D, "Wallet", "Withdrawal", "Deposit", "TreasuryReserve", "Bill"),
            descriptor(DomainCode.E, "Device", "TradeIn", "Order", "DeviceRestore", "ProductCatalog"),
            descriptor(DomainCode.F, "TeamCommission", "PartnerRank", "PayoutPool", "Quota"),
            descriptor(DomainCode.G, "Staking", "Exchange", "NexMarketCurve", "Genesis", "ReinvestIncentive"),
            descriptor(DomainCode.H, "GrowthPhase", "Quest", "Activity", "CheckInNexReward", "WithdrawNexGate"),
            descriptor(DomainCode.I, "Conversation", "Disclosure", "NotificationCampaign", "I18n", "Tutorial"),
            descriptor(DomainCode.J, "KillSwitch", "GeoBlock", "TamperDefense", "EmergencySop"),
            descriptor(DomainCode.K, "RiskCase", "FraudSignal", "DeviceFingerprint", "DecisionEvidence"),
            descriptor(DomainCode.L, "BIReport", "ExportJob", "Funnel", "RegulatoryReport"));

    private static final List<String> DEPRECATED_CAPABILITIES = List.of(
            "PremiumSubscription",
            "NexV2Vault",
            "PointsReward");

    private static final Map<String, UpdateCorrection> UPDATE_CORRECTIONS = List.of(
                    new UpdateCorrection("I9_CROSS_AGENT_TRANSFER", DomainCode.I, "Support conversations can transfer across agents."),
                    new UpdateCorrection("E3B_DEVICE_RESTORE", DomainCode.E, "Mistaken recycled devices can be restored to offline."),
                    new UpdateCorrection("G3_WEEKLY_CURVE", DomainCode.G, "NEX market curve uses seven day keyframes."),
                    new UpdateCorrection("D5_H1_WITHDRAW_NEX_GATE", DomainCode.H, "Withdraw friction gate is owned by H1 and mirrored by D5."),
                    new UpdateCorrection("H5_CHECKIN_NEX_REWARD", DomainCode.H, "Check-in rewards issue NEX instead of points."),
                    new UpdateCorrection("J1_GATE_SHRINK", DomainCode.J, "Kill switch excludes sunset subscription and old vault gates."))
            .stream()
            .collect(Collectors.toUnmodifiableMap(UpdateCorrection::key, Function.identity()));

    private OpsDomainCatalog() {
    }

    public static List<OpsDomainDescriptor> activeDomains() {
        return ACTIVE_DOMAINS;
    }

    public static Set<String> activeCapabilityNames() {
        return ACTIVE_DOMAINS.stream()
                .flatMap(domain -> domain.activeCapabilities().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static List<String> deprecatedCapabilities() {
        return DEPRECATED_CAPABILITIES;
    }

    public static UpdateCorrection updateCorrection(String key) {
        UpdateCorrection correction = UPDATE_CORRECTIONS.get(key);
        if (correction == null) {
            throw new IllegalArgumentException("Unknown ops console update correction: " + key);
        }
        return correction;
    }

    private static OpsDomainDescriptor descriptor(DomainCode code, String... activeCapabilities) {
        return new OpsDomainDescriptor(
                code,
                "ffdd.opsconsole." + code.packageSegment(),
                OpsAdminApi.ADMIN_PREFIX + "/" + code.adminResource(),
                List.of(activeCapabilities));
    }
}
