package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.dto.PayoutVndChannelUpdateRequest;
import ffdd.opsconsole.finance.dto.PayoutVndConfigUpdateRequest;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PayoutVndConfigService {
    public static final String VALUES_KEY = "finance.payout_vnd.values";
    public static final String VERSION_KEY = "finance.payout_vnd.version";
    public static final String PROVIDER_READY_KEY = "finance.payout_vnd.provider_ready";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PlatformConfigFacade config;
    private final VietnamPaymentMapper vietnam;
    private final TreasuryCoverageFacade coverage;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> overview() {
        State state = readState(false);
        if (state == null) return ApiResult.fail(503, "D7_CONFIG_UNAVAILABLE");
        String invalid = validateStored(state.values());
        if (invalid != null) return ApiResult.fail(503, "D7_CONFIG_INVALID");
        FxSource fx = readFx();
        if (fx == null) return ApiResult.fail(503, "D7_D6_SOURCE_UNAVAILABLE");
        return ApiResult.ok(response(state.version(), state.values(), state.providerReady(),
                state.providerStatusAvailable(), fx));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> update(PayoutVndConfigUpdateRequest request) {
        String requestError = validateUpdateRequest(request);
        if (requestError != null) return ApiResult.fail(422, requestError);
        State current = readState(true);
        if (current == null || validateStored(current.values()) != null) {
            return ApiResult.fail(503, "D7_CONFIG_UNAVAILABLE");
        }
        if (!current.version().equals(request.expectedVersion())) {
            return ApiResult.fail(409, "D7_CONFIG_VERSION_CONFLICT");
        }
        FxSource fx = readFx();
        if (fx == null) return ApiResult.fail(503, "D7_D6_SOURCE_UNAVAILABLE");

        Map<String, Object> next = valuesFrom(request, booleanValue(current.values().get("channelEnabled")));
        if (sameOperationalValues(current.values(), next)) return ApiResult.fail(422, "D7_CONFIG_NO_CHANGES");
        if (isInverted(fx, decimal(next.get("sellSpreadPct"))) && !Boolean.TRUE.equals(request.forceInverted())) {
            return ApiResult.fail(422, "D7_PRICE_SPREAD_INVERTED");
        }
        if (amplifies(current.values(), next) && !coverageHealthy()) {
            return ApiResult.fail(409, "D7_TREASURY_COVERAGE_BLOCKED");
        }
        return persist(current, next, "D7_PAYOUT_VND_CONFIG_UPDATED", request.reason(), request.forceInverted());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> updateChannel(PayoutVndChannelUpdateRequest request) {
        if (request == null || request.enabled() == null || request.expectedVersion() == null
                || request.expectedVersion() < 0 || !validReason(request.reason())) {
            return ApiResult.fail(422, "D7_CHANNEL_REQUEST_INVALID");
        }
        State current = readState(true);
        if (current == null || validateStored(current.values()) != null) {
            return ApiResult.fail(503, "D7_CONFIG_UNAVAILABLE");
        }
        if (!current.version().equals(request.expectedVersion())) {
            return ApiResult.fail(409, "D7_CONFIG_VERSION_CONFLICT");
        }
        boolean before = booleanValue(current.values().get("channelEnabled"));
        if (before == request.enabled()) return ApiResult.fail(422, "D7_CHANNEL_NO_CHANGES");
        if (request.enabled() && (!current.providerStatusAvailable() || !current.providerReady())) {
            return ApiResult.fail(409, "D7_PROVIDER_NOT_READY");
        }
        if (request.enabled() && !coverageHealthy()) {
            return ApiResult.fail(409, "D7_TREASURY_COVERAGE_BLOCKED");
        }
        Map<String, Object> next = new LinkedHashMap<>(current.values());
        next.put("channelEnabled", request.enabled());
        next.put("effectiveAt", clock.millis());
        next.put("lastUpdatedBy", AdminActorResolver.resolve("system"));
        return persist(current, next, request.enabled()
                ? "D7_PAYOUT_VND_CHANNEL_ENABLED" : "D7_PAYOUT_VND_CHANNEL_DISABLED",
                request.reason(), false);
    }

    private ApiResult<Map<String, Object>> persist(
            State current,
            Map<String, Object> next,
            String action,
            String reason,
            Boolean forced) {
        long nextVersion = current.version() + 1L;
        try {
            config.upsertAdminValue(VALUES_KEY, objectMapper.writeValueAsString(next), "JSON", "finance",
                    "D7 payout VND config aggregate");
        } catch (Exception ex) {
            throw new IllegalStateException("D7_CONFIG_SERIALIZATION_FAILED", ex);
        }
        config.upsertAdminValue(VERSION_KEY, String.valueOf(nextVersion), "NUMBER", "finance",
                "D7 payout VND config version");
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("PAYOUT_VND_CONFIG")
                .resourceId("D7")
                .actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("system"))
                .riskLevel("CRITICAL")
                .detail(Map.of(
                        "before", auditValues(current.values()),
                        "after", auditValues(next),
                        "beforeVersion", current.version(),
                        "version", nextVersion,
                        "reason", reason.trim(),
                        "forceInverted", Boolean.TRUE.equals(forced)))
                .build());
        FxSource fx = readFx();
        if (fx == null) throw new IllegalStateException("D7_D6_SOURCE_UNAVAILABLE_AFTER_WRITE");
        return ApiResult.ok(response(nextVersion, next, current.providerReady(),
                current.providerStatusAvailable(), fx));
    }

    private State readState(boolean lockVersion) {
        try {
            Optional<String> rawVersion = lockVersion
                    ? config.activeValueForUpdate(VERSION_KEY)
                    : config.activeValue(VERSION_KEY);
            Optional<String> rawValues = config.activeValue(VALUES_KEY);
            if (rawVersion.isEmpty() || rawValues.isEmpty()) return null;
            long version = Long.parseLong(rawVersion.get());
            if (version < 0) return null;
            Map<String, Object> values = objectMapper.readValue(rawValues.get(), MAP_TYPE);
            Optional<String> rawProvider;
            try {
                rawProvider = config.activeValue(PROVIDER_READY_KEY);
            } catch (RuntimeException providerLookupFailed) {
                // Readiness is an enable-only dependency. Degrade it to unavailable
                // so an already-open channel can still be displayed and shut down.
                rawProvider = Optional.empty();
            }
            String provider = rawProvider.orElse("").trim();
            boolean providerStatusAvailable = "true".equals(provider) || "false".equals(provider);
            return new State(version, values, providerStatusAvailable && Boolean.parseBoolean(provider),
                    providerStatusAvailable);
        } catch (Exception ex) {
            return null;
        }
    }

    private FxSource readFx() {
        try {
            Map<String, Object> raw = vietnam.findFxQuoteConfig();
            if (raw == null) return null;
            BigDecimal base = decimal(raw.get("baseRateVndPerUsdt"));
            BigDecimal buySpread = decimal(raw.get("buySpreadPct"));
            if (!between(base, "20000", "35000") || !between(buySpread, "0", "3")) return null;
            return new FxSource(base, buySpread);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Map<String, Object> response(
            long version,
            Map<String, Object> values,
            boolean providerReady,
            boolean providerStatusAvailable,
            FxSource fx) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("baseRateVndPerUsdt", fx.baseRateVndPerUsdt());
        result.put("buySpreadPct", fx.buySpreadPct());
        for (String key : operationalKeys()) result.put(key, values.get(key));
        result.put("providerReady", providerReady);
        result.put("providerStatusAvailable", providerStatusAvailable);
        result.put("defaults", defaults());
        result.put("effectiveAt", Instant.ofEpochMilli(longValue(values.get("effectiveAt"))).toString());
        result.put("lastUpdatedBy", String.valueOf(values.get("lastUpdatedBy")));
        result.put("sources", Map.of(
                "baseRateVndPerUsdt", "D6",
                "buySpreadPct", "D6",
                "d7", "platform-config"));
        return result;
    }

    private Map<String, Object> valuesFrom(PayoutVndConfigUpdateRequest request, boolean channelEnabled) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sellSpreadPct", request.sellSpreadPct());
        values.put("quoteTtlMinWithdraw", request.quoteTtlMinWithdraw());
        values.put("requoteTolerancePct", request.requoteTolerancePct());
        values.put("feeRatePct", request.feeRatePct());
        values.put("feeMinUsd", request.feeMinUsd());
        values.put("feeMaxUsd", request.feeMaxUsd());
        values.put("minAmountUsd", request.minAmountUsd());
        values.put("maxAmountUsd", request.maxAmountUsd());
        values.put("channelEnabled", channelEnabled);
        values.put("effectiveAt", clock.millis());
        values.put("lastUpdatedBy", AdminActorResolver.resolve("system"));
        return values;
    }

    private String validateUpdateRequest(PayoutVndConfigUpdateRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || !validReason(request.reason())) return "D7_CONFIG_REQUEST_INVALID";
        if (!validOperationalValues(request.sellSpreadPct(), request.quoteTtlMinWithdraw(),
                request.requoteTolerancePct(), request.feeRatePct(), request.feeMinUsd(), request.feeMaxUsd(),
                request.minAmountUsd(), request.maxAmountUsd())) return "D7_CONFIG_VALUES_OUT_OF_RANGE";
        if (request.feeMinUsd().signum() > 0 && request.feeMinUsd().compareTo(request.minAmountUsd()) >= 0
                || request.feeMinUsd().compareTo(request.feeMaxUsd()) > 0
                || request.minAmountUsd().compareTo(request.maxAmountUsd()) > 0) {
            return "D7_FEE_LIMIT_RELATION_INVALID";
        }
        return null;
    }

    private String validateStored(Map<String, Object> values) {
        try {
            if (values == null || !(values.get("channelEnabled") instanceof Boolean)
                    || !StringUtils.hasText(String.valueOf(values.get("lastUpdatedBy")))
                    || longValue(values.get("effectiveAt")) <= 0
                    || !validOperationalValues(
                            decimal(values.get("sellSpreadPct")), intValue(values.get("quoteTtlMinWithdraw")),
                            decimal(values.get("requoteTolerancePct")), decimal(values.get("feeRatePct")),
                            decimal(values.get("feeMinUsd")), decimal(values.get("feeMaxUsd")),
                            decimal(values.get("minAmountUsd")), decimal(values.get("maxAmountUsd")))) return "invalid";
            BigDecimal feeMin = decimal(values.get("feeMinUsd"));
            BigDecimal feeMax = decimal(values.get("feeMaxUsd"));
            BigDecimal minAmount = decimal(values.get("minAmountUsd"));
            BigDecimal maxAmount = decimal(values.get("maxAmountUsd"));
            return feeMin.signum() > 0 && feeMin.compareTo(minAmount) >= 0
                    || feeMin.compareTo(feeMax) > 0 || minAmount.compareTo(maxAmount) > 0 ? "invalid" : null;
        } catch (RuntimeException ex) {
            return "invalid";
        }
    }

    private boolean validOperationalValues(
            BigDecimal sellSpread,
            Integer ttl,
            BigDecimal tolerance,
            BigDecimal feeRate,
            BigDecimal feeMin,
            BigDecimal feeMax,
            BigDecimal minAmount,
            BigDecimal maxAmount) {
        return between(sellSpread, "0", "3") && ttl != null && ttl >= 1 && ttl <= 60
                && between(tolerance, "0", "10") && between(feeRate, "0", "5")
                && between(feeMin, "0", "1000") && between(feeMax, "0", "1000")
                && between(minAmount, "0", "10000") && between(maxAmount, "1", "100000")
                && scaleAtMost(sellSpread, 2)
                && scaleAtMost(tolerance, 1) && scaleAtMost(feeRate, 1)
                && multipleOf(feeMin, "0.5") && multipleOf(feeMax, "0.5")
                && scaleAtMost(minAmount, 0) && scaleAtMost(maxAmount, 0);
    }

    private boolean amplifies(Map<String, Object> before, Map<String, Object> after) {
        return decimal(after.get("sellSpreadPct")).compareTo(decimal(before.get("sellSpreadPct"))) < 0
                || intValue(after.get("quoteTtlMinWithdraw")) > intValue(before.get("quoteTtlMinWithdraw"))
                || decimal(after.get("requoteTolerancePct")).compareTo(decimal(before.get("requoteTolerancePct"))) > 0
                || decimal(after.get("feeRatePct")).compareTo(decimal(before.get("feeRatePct"))) < 0
                || decimal(after.get("feeMinUsd")).compareTo(decimal(before.get("feeMinUsd"))) < 0
                || decimal(after.get("feeMaxUsd")).compareTo(decimal(before.get("feeMaxUsd"))) < 0
                || decimal(after.get("minAmountUsd")).compareTo(decimal(before.get("minAmountUsd"))) < 0
                || decimal(after.get("maxAmountUsd")).compareTo(decimal(before.get("maxAmountUsd"))) > 0;
    }

    private boolean coverageHealthy() {
        try {
            TreasuryCoverageSnapshot snapshot = coverage.snapshot();
            return snapshot != null && snapshot.reliable() && snapshot.coverageRatio() != null
                    && snapshot.redlinePct() != null
                    && snapshot.ratioLiabilitiesUsd() != null
                    && snapshot.ratioLiabilitiesUsd().signum() > 0
                    && snapshot.ratioReserveUsd() != null
                    && snapshot.ratioReserveUsd().signum() >= 0
                    && snapshot.coverageRatio().compareTo(snapshot.redlinePct()) >= 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean isInverted(FxSource fx, BigDecimal sellSpread) {
        BigDecimal buy = fx.baseRateVndPerUsdt().multiply(BigDecimal.ONE.add(fx.buySpreadPct().divide(HUNDRED)));
        BigDecimal sell = fx.baseRateVndPerUsdt().multiply(BigDecimal.ONE.subtract(sellSpread.divide(HUNDRED)));
        return sell.compareTo(buy) >= 0;
    }

    private boolean sameOperationalValues(Map<String, Object> before, Map<String, Object> after) {
        for (String key : operationalKeys()) {
            if ("channelEnabled".equals(key)) continue;
            if ("quoteTtlMinWithdraw".equals(key)) {
                if (intValue(before.get(key)) != intValue(after.get(key))) return false;
            } else if (decimal(before.get(key)).compareTo(decimal(after.get(key))) != 0) return false;
        }
        return true;
    }

    private Map<String, Object> auditValues(Map<String, Object> source) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : operationalKeys()) values.put(key, source.get(key));
        return values;
    }

    private Map<String, Object> defaults() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sellSpreadPct", new BigDecimal("1.5"));
        values.put("quoteTtlMinWithdraw", 10);
        values.put("requoteTolerancePct", new BigDecimal("2"));
        values.put("feeRatePct", new BigDecimal("1"));
        values.put("feeMinUsd", new BigDecimal("1"));
        values.put("feeMaxUsd", new BigDecimal("25"));
        values.put("minAmountUsd", new BigDecimal("20"));
        values.put("maxAmountUsd", new BigDecimal("5000"));
        return values;
    }

    private String[] operationalKeys() {
        return new String[]{"sellSpreadPct", "quoteTtlMinWithdraw", "requoteTolerancePct", "feeRatePct",
                "feeMinUsd", "feeMaxUsd", "minAmountUsd", "maxAmountUsd", "channelEnabled"};
    }

    private boolean validReason(String value) {
        return StringUtils.hasText(value) && value.trim().length() >= 8 && value.trim().length() <= 200;
    }

    private boolean between(BigDecimal value, String min, String max) {
        return value != null && value.compareTo(new BigDecimal(min)) >= 0 && value.compareTo(new BigDecimal(max)) <= 0;
    }

    private boolean scaleAtMost(BigDecimal value, int max) {
        return value != null && Math.max(0, value.stripTrailingZeros().scale()) <= max;
    }

    private boolean multipleOf(BigDecimal value, String step) {
        return value != null && value.remainder(new BigDecimal(step)).signum() == 0;
    }

    private BigDecimal decimal(Object value) {
        if (value == null || value instanceof Boolean) throw new IllegalArgumentException("not decimal");
        return new BigDecimal(String.valueOf(value));
    }

    private int intValue(Object value) {
        return decimal(value).intValueExact();
    }

    private long longValue(Object value) {
        return decimal(value).longValueExact();
    }

    private boolean booleanValue(Object value) {
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException("not boolean");
        return bool;
    }

    private record State(
            Long version,
            Map<String, Object> values,
            boolean providerReady,
            boolean providerStatusAvailable) { }
    private record FxSource(BigDecimal baseRateVndPerUsdt, BigDecimal buySpreadPct) { }
}
