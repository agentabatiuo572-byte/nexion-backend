package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.dto.B5AlertSubscriptionRequest;
import ffdd.opsconsole.risk.dto.B5BankRunThresholdRequest;
import ffdd.opsconsole.risk.dto.B5ThresholdPreviewRequest;
import ffdd.opsconsole.risk.dto.B5TriageRequest;
import ffdd.opsconsole.risk.dto.B5SignalStatusRequest;
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
import java.util.Optional;
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
    private static final String SUBSCRIPTION_VERSION_KEY = "risk.alert-subscription.version";
    private static final String SUBSCRIPTION_ACTOR_KEY = "risk.alert-subscription.subscriber";
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
        boolean bankRunCalculable = reserve.signum() > 0;
        BigDecimal bankRunRatio = bankRunCalculable ? ratio(withdrawal24h, reserve) : null;
        BigDecimal pressureNumerator = payout.add(commission);
        boolean pressureCalculable = grossInflow.signum() > 0;
        BigDecimal pressureRatio = pressureCalculable ? ratio(pressureNumerator, grossInflow) : null;
        BankRunThresholdPolicy.Bands bands = BankRunThresholdPolicy.resolve(configFacade);
        long version = configVersion(THRESHOLD_VERSION_KEY, false);

        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (coverage == null || !coverage.reliable()) {
            throw new BizException(500, "B5_COVERAGE_SOURCE_UNAVAILABLE");
        }

        List<Map<String, Object>> backlog = canonicalBacklog(mapper.withdrawalBacklog());
        List<Map<String, Object>> abnormal = canonicalAbnormal(mapper.abnormalAccountCategories());
        long abnormalCount = mapper.abnormalAccountCount();
        if (abnormalCount < 0) {
            throw new BizException(500, "B5_SOURCE_COUNT_INVALID");
        }
        long backlogCount = backlog.stream().mapToLong(row -> whole(row.get("count"))).sum();
        BigDecimal backlogAmount = backlog.stream()
                .map(row -> decimal(row.get("amountUsdt")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long overdue = backlog.stream().mapToLong(row -> whole(row.get("overSlaCount"))).sum();
        LocalDateTime dayStart = LocalDateTime.now(clock).toLocalDate().minusDays(7).atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(8);
        LocalDateTime alertStart = dayEnd.minusDays(7);
        if (mapper.unknownSeverityCount(alertStart, dayEnd) > 0) {
            throw new BizException(500, "B5_UNKNOWN_SEVERITY");
        }
        if (mapper.unknownWithdrawalStatusCount() > 0) {
            throw new BizException(500, "B5_UNKNOWN_WITHDRAWAL_STATUS");
        }

        Map<String, Object> bankrun = section(
                "ratio24h", bankRunRatio,
                "ratioCalculable", bankRunCalculable,
                "light", bankRunCalculable
                        ? thresholdLight(bankRunRatio, bands.yellowPct(), bands.redlinePct())
                        : withdrawal24h.signum() > 0 ? "red" : "unavailable",
                "withdraw24hUsdt", money(withdrawal24h),
                "reserveUsdt", money(reserve),
                "ratioWithdraw24hUsdt", withdrawal24h,
                "ratioReserveUsdt", reserve,
                "pressureRatio", pressureRatio,
                "pressureCalculable", pressureCalculable,
                "pressureRedLine", PRESSURE_RED_LINE,
                "pressureLight", !pressureCalculable
                        ? (pressureNumerator.signum() > 0 ? "red" : "yellow")
                        : pressureRatio.compareTo(PRESSURE_RED_LINE) >= 0 ? "red" : "green",
                "yellowPct", bands.yellowPct(),
                "redPct", bands.redlinePct(),
                "version", version);
        BigDecimal coverageRatioDenominator = safe(coverage.ratioLiabilitiesUsd());
        boolean coverageCalculable = coverageRatioDenominator.signum() > 0;
        Map<String, Object> coverageView = section(
                "ratio", coverageCalculable ? safe(coverage.coverageRatio()) : null,
                "light", coverageCalculable ? coverageLight(coverage) : "unavailable",
                "redlinePct", safe(coverage.redlinePct()),
                "reserveUsdt", money(coverage.reserveUsd()),
                "liabilitiesUsdt", money(coverage.liabilitiesUsd()),
                "ratioReserveUsdt", safe(coverage.ratioReserveUsd()),
                "ratioLiabilitiesUsdt", safe(coverage.ratioLiabilitiesUsd()),
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
                "pressureHistory", pressureHistory(mapper.pressureWindows(dayStart, dayEnd)),
                "alertSeverity", mapper.alertSeverity(alertStart, dayEnd),
                "alertVolume", mapper.alertVolume(alertStart, dayEnd),
                "recentAlerts", recentAlerts(mapper.recentSignals(alertStart, dayEnd)),
                "sources", List.of(
                        "B1:TreasuryCoverageFacade",
                        "D2:nx_withdrawal_order",
                        "K:nx_risk_signal",
                        "K:nx_risk_signal.severity",
                        "B1:nx_wallet_ledger pressure windows",
                        "J1:killswitch.*",
                        "A3:risk.bankrun-*"));
    }

    private List<Map<String, Object>> recentAlerts(List<Map<String, Object>> rows) {
        if (rows == null) {
            throw new BizException(500, "B5_ALERT_FEED_SOURCE_UNAVAILABLE");
        }
        return rows.stream().map(row -> {
            String signalType = text(row.get("signalType"));
            String normalized = signalType == null ? "" : signalType.toLowerCase(Locale.ROOT);
            String label;
            String target;
            if (normalized.contains("multi_account")) {
                label = "反多账户命中";
                target = "/risk/multi-account";
            } else if (normalized.contains("arbitrage") || normalized.contains("trial_cycle")
                    || normalized.contains("leaderboard_velocity")) {
                label = "套利或循环行为命中";
                target = "/risk/abuse";
            } else if (normalized.contains("withdraw")) {
                label = "提现风险命中";
                target = "/finance/withdrawals";
            } else if (normalized.contains("tamper")) {
                label = "篡改风险命中";
                target = "/emergency/tamper";
            } else {
                label = "风险信号命中";
                target = "/risk/scoring";
            }
            return section(
                    "signalNo", requireSignalField(row.get("signalNo"), "signalNo"),
                    "level", requireSignalField(row.get("level"), "level"),
                    "message", label,
                    "userId", whole(row.get("userId")),
                    "createdAt", requireSignalField(row.get("createdAt"), "createdAt"),
                    "target", target,
                    "handlingStatusAvailable", true,
                    "handlingStatus", requireSignalField(row.get("handlingStatus"), "handlingStatus"),
                    "handlingVersion", whole(row.get("handlingVersion")),
                    "deliveryStatus", requireSignalField(row.get("deliveryStatus"), "deliveryStatus"));
        }).toList();
    }

    private List<Map<String, Object>> pressureHistory(List<Map<String, Object>> rows) {
        if (rows == null) {
            throw new BizException(500, "B5_PRESSURE_HISTORY_SOURCE_UNAVAILABLE");
        }
        return rows.stream().map(row -> section(
                "label", requireSignalField(row.get("label"), "pressureHistory.label"),
                // MyBatis omits NULL select aliases from a result map. Reinsert the key so the
                // public contract can distinguish an uncomputable denominator from a missing field.
                "ratio", row.get("ratio") == null ? null : decimal(row.get("ratio"))))
                .toList();
    }

    private String requireSignalField(Object value, String field) {
        String result = text(value);
        if (result == null) throw new BizException(500, "B5_ALERT_FEED_SOURCE_INVALID:" + field);
        return result;
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
        BigDecimal withdrawal24h = decimal(money.get("withdraw24hUsdt"));
        BigDecimal reserve = decimal(money.get("reserveUsdt"));
        boolean calculable = reserve.signum() > 0;
        BigDecimal ratio = calculable ? ratio(withdrawal24h, reserve) : null;
        return ApiResult.ok(section(
                "ratio24h", ratio,
                "ratioCalculable", calculable,
                "light", calculable
                        ? thresholdLight(ratio, thresholds.yellow(), thresholds.red())
                        : withdrawal24h.signum() > 0 ? "red" : "unavailable",
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

    public ApiResult<List<Map<String, Object>>> alertInbox() {
        String subscriber = AdminActorResolver.resolve("");
        requireActor(subscriber);
        return ApiResult.ok(mapper.subscriberInbox(subscriber, 100));
    }

    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> acknowledgeAlert(String idempotencyKey, long deliveryId) {
        String subscriber = AdminActorResolver.resolve("");
        requireActor(subscriber);
        if (!StringUtils.hasText(idempotencyKey)) throw new BizException(400, "IDEMPOTENCY_KEY_REQUIRED");
        String hash = hash(deliveryId + "|" + subscriber);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "B5_INBOX_ACK:" + deliveryId, idempotencyKey.trim(), hash, ApiResult.class,
                () -> acknowledgeAlertOnce(deliveryId, subscriber));
    }

    private ApiResult<Map<String, Object>> acknowledgeAlertOnce(long deliveryId, String subscriber) {
        if (deliveryId <= 0 || mapper.acknowledgeInbox(deliveryId, subscriber) != 1) {
            throw new BizException(404, "B5_INBOX_DELIVERY_NOT_FOUND");
        }
        auditRequired("B5_INBOX_ACKNOWLEDGED", "B5_ALERT_DELIVERY", String.valueOf(deliveryId), subscriber,
                section("subscriber", subscriber, "deliveryId", deliveryId));
        return ApiResult.ok(section("deliveryId", deliveryId, "acknowledged", true));
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
        if (email) {
            throw new BizException(503, "B5_EMAIL_PROVIDER_UNAVAILABLE");
        }
        if (webhook && (webhookUrl == null || !webhookUrl.startsWith("https://") || webhookUrl.length() > 500)) {
            throw new BizException(400, "B5_WEBHOOK_URL_INVALID");
        }
        if (webhook && !StringUtils.hasText(configFacade
                .activeValue(B5RiskAlertDeliveryService.WEBHOOK_EGRESS_PROXY_KEY).orElse(""))) {
            throw new BizException(503, "B5_WEBHOOK_EGRESS_PROXY_UNAVAILABLE");
        }
        if (webhook) {
            try {
                Set<String> allowlist = java.util.Arrays.stream(configFacade
                        .activeValue(B5RiskAlertDeliveryService.WEBHOOK_ALLOWLIST_KEY).orElse("").split(","))
                        .map(String::trim).filter(StringUtils::hasText)
                        .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
                B5RiskAlertDeliveryService.requireAllowedPublicHttps(java.net.URI.create(webhookUrl), allowlist);
            } catch (Exception ex) {
                throw new BizException(400, "B5_WEBHOOK_URL_INVALID");
            }
        }
        String actor = AdminActorResolver.resolve(request.operator());
        requireActor(actor);
        String channels = String.join(",", enabledChannels(inApp, email, webhook));
        long expectedVersion = request.expectedVersion() == null ? -1 : request.expectedVersion();
        String hash = hash(channels + "|" + (webhookUrl == null ? "" : webhookUrl) + "|"
                + expectedVersion + "|" + actor);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                SUBSCRIPTION_SCOPE, idempotencyKey, hash, ApiResult.class,
                () -> updateSubscriptionNew(channels, webhook ? webhookUrl : "", expectedVersion, actor));
    }

    @Transactional
    ApiResult<Map<String, Object>> updateSubscriptionNew(
            String channels, String webhookUrl, long expectedVersion, String actor) {
        long currentVersion = configVersion(SUBSCRIPTION_VERSION_KEY, true);
        if (expectedVersion != currentVersion) {
            throw new BizException(409, "B5_SUBSCRIPTION_VERSION_CONFLICT");
        }
        Map<String, Object> before = subscriptionView();
        configFacade.upsertAdminValue(
                SUBSCRIPTION_CHANNELS_KEY, channels,
                "STRING", "risk_alert_subscription", "B1/B5 shared alert channels");
        configFacade.upsertAdminValue(
                SUBSCRIPTION_WEBHOOK_KEY, webhookUrl,
                "STRING", "risk_alert_subscription", "B1/B5 shared Webhook endpoint");
        configFacade.upsertAdminValue(
                SUBSCRIPTION_VERSION_KEY, String.valueOf(currentVersion + 1),
                "NUMBER", "risk_alert_subscription", "B1/B5 shared subscription version");
        configFacade.upsertAdminValue(
                SUBSCRIPTION_ACTOR_KEY, actor,
                "STRING", "risk_alert_subscription", "B5 alert subscriber identity");
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> updateSignalStatus(
            String idempotencyKey, String signalNo, B5SignalStatusRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) throw new BizException(400, "IDEMPOTENCY_KEY_REQUIRED");
        if (!StringUtils.hasText(signalNo) || request == null) throw new BizException(400, "B5_SIGNAL_STATUS_REQUIRED");
        String target = text(request.targetStatus());
        String expected = text(request.expectedStatus());
        if (!Set.of("handled", "resolved").contains(target)
                || !Set.of("open", "handled").contains(expected)
                || !("open".equals(expected) && "handled".equals(target)
                || "handled".equals(expected) && "resolved".equals(target))) {
            throw new BizException(409, "B5_SIGNAL_STATUS_TRANSITION_INVALID");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new BizException(400, "B5_SIGNAL_VERSION_REQUIRED");
        }
        String reason = requireReason(request.reason());
        String actor = AdminActorResolver.resolve(request.operator());
        requireActor(actor);
        String normalizedSignal = signalNo.trim();
        String hash = hash(normalizedSignal + "|" + expected + "|" + target + "|"
                + request.expectedVersion() + "|" + reason + "|" + actor);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "B5_SIGNAL_STATUS:" + normalizedSignal, idempotencyKey.trim(), hash, ApiResult.class,
                () -> updateSignalStatusOnce(normalizedSignal, expected, target,
                        request.expectedVersion(), reason, actor));
    }

    @Transactional
    ApiResult<Map<String, Object>> updateSignalStatusOnce(
            String signalNo, String expectedStatus, String targetStatus,
            long expectedVersion, String reason, String actor) {
        if (mapper.signalExists(signalNo) != 1) throw new BizException(404, "B5_SIGNAL_NOT_FOUND");
        mapper.ensureSignalDisposition(signalNo);
        B5RiskRadarMapper.SignalDispositionRecord current = mapper.lockSignalDisposition(signalNo);
        if (current == null || !expectedStatus.equals(current.status()) || expectedVersion != current.version()) {
            throw new BizException(409, "B5_SIGNAL_VERSION_CONFLICT");
        }
        if (mapper.updateSignalDisposition(
                signalNo, targetStatus, expectedStatus, expectedVersion, actor, reason) != 1) {
            throw new BizException(409, "B5_SIGNAL_VERSION_CONFLICT");
        }
        auditRequired("B5_SIGNAL_STATUS_CHANGED", "B5_RISK_SIGNAL", signalNo, actor, section(
                "before", expectedStatus, "after", targetStatus,
                "beforeVersion", expectedVersion, "afterVersion", expectedVersion + 1,
                "reason", reason));
        return ApiResult.ok(section("signalNo", signalNo, "handlingStatus", targetStatus,
                "handlingVersion", expectedVersion + 1));
    }

    private List<Map<String, Object>> killSwitches() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> states = new LinkedHashMap<>();
        List<Map<String, Object>> sourceRows = mapper.killSwitchStates();
        if (sourceRows == null) {
            throw new BizException(503, "B5_KILL_SWITCH_SOURCE_UNAVAILABLE");
        }
        for (Map<String, Object> row : sourceRows) {
            String key = text(row.get("gateKey"));
            if (key != null) states.put(key, text(row.get("settingValue")));
        }
        if (!states.keySet().containsAll(GATES)) {
            throw new BizException(503, "B5_KILL_SWITCH_SOURCE_UNAVAILABLE");
        }
        for (String gate : GATES) {
            String rawValue = states.get(gate);
            boolean defaulted = rawValue == null;
            String value = defaulted ? "" : rawValue.toLowerCase(Locale.ROOT);
            if (!defaulted && !Set.of("enabled", "enable", "on", "true", "1",
                    "disabled", "disable", "off", "false", "0").contains(value)) {
                throw new BizException(503, "B5_KILL_SWITCH_SOURCE_INVALID");
            }
            boolean enabled = KillSwitchState.enabled(Optional.ofNullable(rawValue), Optional.empty());
            rows.add(section(
                    "key", gate,
                    "enabled", enabled,
                    "defaulted", defaulted,
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
        Map<String, Object> source = indexed.get(category);
        return section(
                "category", category,
                "label", label,
                "count", source == null ? 0L : whole(source.get("count")));
    }

    private Map<String, Object> subscriptionView() {
        Set<String> channels = Set.of(configFacade.activeValue(SUBSCRIPTION_CHANNELS_KEY)
                .orElse("inApp").split(","));
        String webhookUrl = configFacade.activeValue(SUBSCRIPTION_WEBHOOK_KEY).orElse("");
        String subscriber = configFacade.activeValue(SUBSCRIPTION_ACTOR_KEY).orElse("unassigned");
        long version = configVersion(SUBSCRIPTION_VERSION_KEY, false);
        return section(
                "inApp", channels.contains("inApp"),
                "email", channels.contains("email"),
                "emailMode", "disabled",
                "webhook", channels.contains("webhook"),
                "webhookMode", StringUtils.hasText(configFacade
                        .activeValue(B5RiskAlertDeliveryService.WEBHOOK_EGRESS_PROXY_KEY).orElse(""))
                        ? "controlled-proxy" : "disabled",
                "webhookUrl", webhookUrl,
                "version", version,
                "subscriber", subscriber,
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
            throw new BizException(500, "B5_CONFIG_VERSION_INVALID");
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
        if (value == null) throw new BizException(500, "B5_SOURCE_VALUE_INVALID");
        try {
            BigDecimal parsed = new BigDecimal(String.valueOf(value));
            if (parsed.signum() < 0) throw new BizException(500, "B5_SOURCE_VALUE_INVALID");
            return parsed;
        } catch (RuntimeException ex) {
            if (ex instanceof BizException bizException) throw bizException;
            throw new BizException(500, "B5_SOURCE_VALUE_INVALID");
        }
    }

    private long whole(Object value) {
        BigDecimal parsed = decimal(value);
        try {
            return parsed.longValueExact();
        } catch (ArithmeticException ex) {
            throw new BizException(500, "B5_SOURCE_COUNT_INVALID");
        }
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
