package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.dto.B5AlertSubscriptionRequest;
import ffdd.opsconsole.risk.dto.B5BankRunThresholdRequest;
import ffdd.opsconsole.risk.dto.B5ThresholdPreviewRequest;
import ffdd.opsconsole.risk.dto.B5TriageRequest;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.application.BankRunThresholdPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsRiskRadarService {
    public static final BigDecimal PRESSURE_RED_LINE = new BigDecimal("0.7");
    private static final String THRESHOLD_VERSION_KEY = "risk.bankrun-threshold-version";
    private static final String SUBSCRIPTION_CHANNELS_KEY = "risk.alert-subscription.channels";
    private static final String SUBSCRIPTION_WEBHOOK_KEY = "risk.alert-subscription.webhook-url";
    private static final String THRESHOLD_SCOPE = "B5_BANKRUN_THRESHOLDS";
    private static final String SUBSCRIPTION_SCOPE = "B5_ALERT_SUBSCRIPTION";
    private static final String TRIAGE_SCOPE = "B5_TRIAGE";
    private static final List<String> GATES = List.of("withdraw", "staking", "genesis", "exchange", "trial");
    private static final List<String> BACKLOG_STATES = List.of("submitted", "review-passed", "processing");
    private static final Map<String, String> TRIAGE_TARGETS = Map.of(
            "bankrun", "/finance/withdrawals",
            "abnormal-accounts", "/risk/multi-account",
            "withdraw-backlog", "/finance/withdrawals",
            "kill-switches", "/emergency/kill-switch",
            "coverage", "/overview/dual-ledger");

    private final B5RiskRadarMapper mapper;
    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AdminIdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final AdminOperatorRoleResolver roleResolver;
    private final Clock clock;

    public ApiResult<Map<String, Object>> radar() {
        return ApiResult.ok(radarView());
    }

    public Map<String, Object> radarView() {
        Map<String, Object> money = mapper.moneySnapshot();
        if (money == null || !money.keySet().containsAll(Set.of(
                "withdraw24hUsdt", "reserveUsdt", "payoutUsdt", "commissionUsdt", "grossInflowUsdt"))) {
            throw new BizException(500, "B5_MONEY_SOURCE_UNAVAILABLE");
        }
        BigDecimal withdrawal24h = decimal(money.get("withdraw24hUsdt"));
        BigDecimal reserve = decimal(money.get("reserveUsdt"));
        BigDecimal payout = decimal(money.get("payoutUsdt"));
        BigDecimal commission = decimal(money.get("commissionUsdt"));
        BigDecimal grossInflow = decimal(money.get("grossInflowUsdt"));
        BigDecimal bankRunRatio = ratio(withdrawal24h, reserve);
        BigDecimal pressureRatio = ratio(payout.add(commission), grossInflow);
        BankRunThresholdPolicy.Bands bands = BankRunThresholdPolicy.resolve(configFacade);
        long version = configVersion(THRESHOLD_VERSION_KEY, false);

        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (coverage == null || !coverage.reliable()) {
            throw new BizException(500, "B5_COVERAGE_SOURCE_UNAVAILABLE");
        }

        List<Map<String, Object>> backlog = canonicalBacklog(mapper.withdrawalBacklog());
        List<Map<String, Object>> abnormal = canonicalAbnormal(mapper.abnormalAccountCategories());
        long abnormalCount = abnormal.stream().mapToLong(row -> whole(row.get("count"))).sum();
        long backlogCount = backlog.stream().mapToLong(row -> whole(row.get("count"))).sum();
        BigDecimal backlogAmount = backlog.stream()
                .map(row -> decimal(row.get("amountUsdt")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long overdue = backlog.stream().mapToLong(row -> whole(row.get("overSlaCount"))).sum();

        Map<String, Object> bankrun = section(
                "ratio24h", bankRunRatio,
                "light", thresholdLight(bankRunRatio, bands.yellowPct(), bands.redlinePct()),
                "withdraw24hUsdt", money(withdrawal24h),
                "reserveUsdt", money(reserve),
                "pressureRatio", pressureRatio,
                "pressureRedLine", PRESSURE_RED_LINE,
                "pressureLight", pressureRatio.compareTo(PRESSURE_RED_LINE) >= 0 ? "red" : "green",
                "yellowPct", bands.yellowPct(),
                "redPct", bands.redlinePct(),
                "version", version);
        Map<String, Object> coverageView = section(
                "ratio", safe(coverage.coverageRatio()),
                "light", coverageLight(coverage),
                "redlinePct", safe(coverage.redlinePct()),
                "reserveUsdt", money(coverage.reserveUsd()),
                "liabilitiesUsdt", money(coverage.liabilitiesUsd()),
                "source", "B1:TreasuryCoverageFacade");

        return section(
                "generatedAt", LocalDateTime.now(clock).toString(),
                "bankrun", bankrun,
                "abnormalAccounts", section(
                        "count", abnormalCount,
                        "byCategory", abnormal,
                        "source", "K:nx_risk_signal+nx_withdrawal_order"),
                "withdrawBacklog", section(
                        "byState", backlog,
                        "totalCount", backlogCount,
                        "totalAmountUsdt", money(backlogAmount),
                        "slaHours", 48,
                        "overSlaCount", overdue,
                        "light", overdue > 0 ? "yellow" : "green",
                        "source", "D2:nx_withdrawal_order"),
                "killSwitches", killSwitches(),
                "coverage", coverageView,
                "sources", List.of(
                        "B1:TreasuryCoverageFacade",
                        "D2:nx_withdrawal_order",
                        "K:nx_risk_signal",
                        "J1:killswitch.*",
                        "A3:risk.bankrun-*"));
    }

    public ApiResult<Map<String, Object>> preview(B5ThresholdPreviewRequest request) {
        if (request == null) {
            throw new BizException(400, "BANKRUN_THRESHOLD_REQUIRED");
        }
        Thresholds thresholds = validateThresholds(request.yellowPct(), request.redPct());
        long currentVersion = configVersion(THRESHOLD_VERSION_KEY, false);
        requireExpectedVersion(request.expectedVersion(), currentVersion);
        Map<String, Object> money = mapper.moneySnapshot();
        if (money == null) {
            throw new BizException(500, "B5_MONEY_SOURCE_UNAVAILABLE");
        }
        BigDecimal ratio = ratio(decimal(money.get("withdraw24hUsdt")), decimal(money.get("reserveUsdt")));
        return ApiResult.ok(section(
                "ratio24h", ratio,
                "light", thresholdLight(ratio, thresholds.yellow(), thresholds.red()),
                "yellowPct", thresholds.yellow(),
                "redPct", thresholds.red(),
                "expectedVersion", currentVersion));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> updateThresholds(
            String idempotencyKey, B5BankRunThresholdRequest request) {
        if (request == null) {
            throw new BizException(400, "BANKRUN_THRESHOLD_REQUIRED");
        }
        String reason = requireReason(request.reason());
        Thresholds thresholds = validateThresholds(request.yellowPct(), request.redPct());
        String actor = AdminActorResolver.resolve(request.operator());
        requireActor(actor);
        String hash = hash(thresholds.yellow() + "|" + thresholds.red() + "|"
                + request.expectedVersion() + "|" + reason + "|" + actor);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                THRESHOLD_SCOPE, idempotencyKey, hash, ApiResult.class,
                () -> updateThresholdsNew(thresholds, request.expectedVersion(), reason, actor));
    }

    @Transactional
    ApiResult<Map<String, Object>> updateThresholdsNew(
            Thresholds thresholds, Long expectedVersion, String reason, String actor) {
        long currentVersion = configVersion(THRESHOLD_VERSION_KEY, true);
        requireExpectedVersion(expectedVersion, currentVersion);
        BankRunThresholdPolicy.Bands before = BankRunThresholdPolicy.resolve(configFacade);
        configFacade.upsertAdminValue(
                BankRunThresholdPolicy.YELLOW_CONFIG_KEY, plain(thresholds.yellow()),
                "NUMBER", "risk", "B5 bank-run warning threshold");
        configFacade.upsertAdminValue(
                BankRunThresholdPolicy.REDLINE_CONFIG_KEY, plain(thresholds.red()),
                "NUMBER", "risk", "B5/J1 R1 shared bank-run redline");
        configFacade.upsertAdminValue(
                THRESHOLD_VERSION_KEY, String.valueOf(currentVersion + 1),
                "NUMBER", "risk", "B5 optimistic concurrency version");
        auditRequired("B5_BANKRUN_THRESHOLDS_CHANGED", "B5_THRESHOLD", "bankrun", actor, section(
                "role", roleResolver.resolve(),
                "before", section("yellowPct", before.yellowPct(), "redPct", before.redlinePct()),
                "after", section("yellowPct", thresholds.yellow(), "redPct", thresholds.red()),
                "reason", reason,
                "version", currentVersion + 1,
                "linkedDomain", "J1:R1"));
        return radar();
    }

    public ApiResult<Map<String, Object>> subscription() {
        return ApiResult.ok(subscriptionView());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> updateSubscription(
            String idempotencyKey, B5AlertSubscriptionRequest request) {
        if (request == null) {
            throw new BizException(400, "B5_SUBSCRIPTION_REQUIRED");
        }
        boolean inApp = Boolean.TRUE.equals(request.inApp());
        boolean email = Boolean.TRUE.equals(request.email());
        boolean webhook = Boolean.TRUE.equals(request.webhook());
        String webhookUrl = text(request.webhookUrl());
        if (!inApp && !email && !webhook) {
            throw new BizException(400, "B5_SUBSCRIPTION_CHANNEL_REQUIRED");
        }
        if (webhook && (webhookUrl == null || !webhookUrl.startsWith("https://") || webhookUrl.length() > 500)) {
            throw new BizException(400, "B5_WEBHOOK_URL_INVALID");
        }
        String actor = AdminActorResolver.resolve(request.operator());
        requireActor(actor);
        String channels = String.join(",", enabledChannels(inApp, email, webhook));
        String hash = hash(channels + "|" + (webhookUrl == null ? "" : webhookUrl) + "|" + actor);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                SUBSCRIPTION_SCOPE, idempotencyKey, hash, ApiResult.class,
                () -> updateSubscriptionNew(channels, webhook ? webhookUrl : "", actor));
    }

    @Transactional
    ApiResult<Map<String, Object>> updateSubscriptionNew(String channels, String webhookUrl, String actor) {
        Map<String, Object> before = subscriptionView();
        configFacade.upsertAdminValue(
                SUBSCRIPTION_CHANNELS_KEY, channels,
                "STRING", "risk_alert_subscription", "B1/B5 shared alert channels");
        configFacade.upsertAdminValue(
                SUBSCRIPTION_WEBHOOK_KEY, webhookUrl,
                "STRING", "risk_alert_subscription", "B1/B5 shared Webhook endpoint");
        Map<String, Object> after = subscriptionView();
        auditRequired("B5_ALERT_SUBSCRIPTION_CHANGED", "B5_SUBSCRIPTION", "shared", actor, section(
                "role", roleResolver.resolve(),
                "before", maskedSubscription(before),
                "after", maskedSubscription(after)));
        return ApiResult.ok(after);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> triage(String idempotencyKey, B5TriageRequest request) {
        if (request == null) {
            throw new BizException(400, "B5_TRIAGE_REQUIRED");
        }
        String dimension = text(request.dimension());
        String target = text(request.target());
        if (dimension == null || !TRIAGE_TARGETS.containsKey(dimension)
                || !TRIAGE_TARGETS.get(dimension).equals(target)) {
            throw new BizException(400, "B5_TRIAGE_TARGET_INVALID");
        }
        String role = roleResolver.resolveCode();
        if ("FINANCE".equals(role) && !Set.of("bankrun", "withdraw-backlog", "coverage").contains(dimension)) {
            throw new BizException(403, "B5_TRIAGE_FORBIDDEN");
        }
        String actor = AdminActorResolver.resolve(request.operator());
        requireActor(actor);
        String hash = hash(dimension + "|" + target + "|" + actor);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                TRIAGE_SCOPE, idempotencyKey, hash, ApiResult.class, () -> {
                    auditRequired("B5_TRIAGE_JUMPED", "B5_DIMENSION", dimension, actor, section(
                            "role", roleResolver.resolve(),
                            "dimension", dimension,
                            "target", target));
                    return ApiResult.ok(section("dimension", dimension, "target", target));
                });
    }

    private List<Map<String, Object>> killSwitches() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String gate : GATES) {
            boolean enabled = KillSwitchState.enabled(
                    configFacade.activeValue("killswitch." + gate),
                    configFacade.activeValue("J.killswitch." + gate));
            rows.add(section(
                    "key", gate,
                    "enabled", enabled,
                    "light", enabled ? "green" : "red",
                    "source", "J1:killswitch." + gate));
        }
        return rows;
    }

    private List<Map<String, Object>> canonicalBacklog(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                String state = text(row.get("state"));
                if (BACKLOG_STATES.contains(state)) {
                    indexed.put(state, row);
                }
            }
        }
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (String state : BACKLOG_STATES) {
            Map<String, Object> row = indexed.getOrDefault(state, Map.of());
            canonical.add(section(
                    "state", state,
                    "count", whole(row.get("count")),
                    "amountUsdt", money(decimal(row.get("amountUsdt"))),
                    "overSlaCount", whole(row.get("overSlaCount")),
                    "slaHours", 48));
        }
        return canonical;
    }

    private List<Map<String, Object>> canonicalAbnormal(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                String category = text(row.get("category"));
                if (category != null) {
                    indexed.put(category, row);
                }
            }
        }
        return List.of(
                abnormal(indexed, "multi-account", "反多账户命中"),
                abnormal(indexed, "arbitrage", "套利可疑"),
                abnormal(indexed, "trial-cycle", "Trial 循环养号"),
                abnormal(indexed, "withdraw-held", "提现冻结"));
    }

    private Map<String, Object> abnormal(
            Map<String, Map<String, Object>> indexed, String category, String label) {
        return section(
                "category", category,
                "label", label,
                "count", whole(indexed.getOrDefault(category, Map.of()).get("count")));
    }

    private Map<String, Object> subscriptionView() {
        Set<String> channels = Set.of(configFacade.activeValue(SUBSCRIPTION_CHANNELS_KEY)
                .orElse("inApp,email").split(","));
        String webhookUrl = configFacade.activeValue(SUBSCRIPTION_WEBHOOK_KEY).orElse("");
        return section(
                "inApp", channels.contains("inApp"),
                "email", channels.contains("email"),
                "webhook", channels.contains("webhook"),
                "webhookUrl", webhookUrl,
                "sharedWith", "B1");
    }

    private Map<String, Object> maskedSubscription(Map<String, Object> value) {
        return section(
                "inApp", value.get("inApp"),
                "email", value.get("email"),
                "webhook", value.get("webhook"),
                "webhookConfigured", StringUtils.hasText(String.valueOf(value.get("webhookUrl"))));
    }

    private List<String> enabledChannels(boolean inApp, boolean email, boolean webhook) {
        List<String> result = new ArrayList<>();
        if (inApp) result.add("inApp");
        if (email) result.add("email");
        if (webhook) result.add("webhook");
        return result;
    }

    private Thresholds validateThresholds(String yellowValue, String redValue) {
        BankRunThresholdPolicy.Bands current = BankRunThresholdPolicy.resolve(configFacade);
        BigDecimal yellow = parseThreshold(
                yellowValue, current.yellowPct(),
                BankRunThresholdPolicy.MIN_YELLOW_PCT, BankRunThresholdPolicy.MAX_YELLOW_PCT,
                "BANKRUN_YELLOW_INVALID");
        BigDecimal red = parseThreshold(
                redValue, current.redlinePct(),
                BankRunThresholdPolicy.MIN_REDLINE_PCT, BankRunThresholdPolicy.MAX_REDLINE_PCT,
                "BANKRUN_RED_INVALID");
        if (red.compareTo(yellow) <= 0) {
            throw new BizException(400, "BANKRUN_REDLINE_MUST_EXCEED_YELLOW");
        }
        return new Thresholds(yellow, red);
    }

    private BigDecimal parseThreshold(
            String value, BigDecimal fallback, BigDecimal min, BigDecimal max, String error) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            if (parsed.compareTo(min) < 0 || parsed.compareTo(max) > 0) {
                throw new BizException(400, error);
            }
            return parsed.stripTrailingZeros();
        } catch (NumberFormatException ex) {
            throw new BizException(400, error);
        }
    }

    private void requireExpectedVersion(Long expectedVersion, long currentVersion) {
        if (expectedVersion == null || expectedVersion != currentVersion) {
            throw new BizException(409, "B5_THRESHOLD_VERSION_CONFLICT");
        }
    }

    private String requireReason(String value) {
        String reason = text(value);
        if (reason == null || reason.length() < 8 || reason.length() > 200) {
            throw new BizException(400, "REASON_REQUIRED");
        }
        return reason;
    }

    private void requireActor(String actor) {
        if (!StringUtils.hasText(actor)) {
            throw new BizException(403, "B5_OPERATOR_FORBIDDEN");
        }
    }

    private long configVersion(String key, boolean locked) {
        String raw = (locked ? configFacade.activeValueForUpdate(key) : configFacade.activeValue(key)).orElse("0");
        try {
            long version = Long.parseLong(raw);
            if (version < 0) throw new NumberFormatException();
            return version;
        } catch (NumberFormatException ex) {
            throw new BizException(500, "B5_THRESHOLD_VERSION_INVALID");
        }
    }

    private String thresholdLight(BigDecimal ratio, BigDecimal yellowPct, BigDecimal redPct) {
        BigDecimal pct = ratio.multiply(new BigDecimal("100"));
        if (pct.compareTo(redPct) >= 0) return "red";
        if (pct.compareTo(yellowPct) >= 0) return "yellow";
        return "green";
    }

    private String coverageLight(TreasuryCoverageSnapshot coverage) {
        BigDecimal ratio = safe(coverage.coverageRatio());
        BigDecimal redline = safe(coverage.redlinePct());
        if (ratio.compareTo(redline) < 0) return "red";
        if (ratio.compareTo(redline.add(new BigDecimal("10"))) < 0) return "yellow";
        return "green";
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal safeNumerator = safe(numerator);
        BigDecimal safeDenominator = safe(denominator);
        if (safeDenominator.signum() == 0) {
            return safeNumerator.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE;
        }
        return safeNumerator.divide(safeDenominator, 4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        try {
            return safe(new BigDecimal(String.valueOf(value)));
        } catch (RuntimeException ex) {
            throw new BizException(500, "B5_SOURCE_VALUE_INVALID");
        }
    }

    private long whole(Object value) {
        return decimal(value).longValue();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private BigDecimal money(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String text(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) return null;
        return String.valueOf(value).trim();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("B5_REQUEST_HASH_FAILED", ex);
        }
    }

    private void auditRequired(
            String action, String resourceType, String resourceId, String actor, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(actor)
                .riskLevel("HIGH")
                .result("SUCCESS")
                .detail(detail)
                .build());
    }

    private Map<String, Object> section(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }

    record Thresholds(BigDecimal yellow, BigDecimal red) {
    }
}
