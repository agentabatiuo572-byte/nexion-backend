package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.GrowthPublicStatsUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class GrowthPublicStatsService {
    public static final String VALUES_KEY = "growth.public_stats.values";
    public static final String VERSION_KEY = "growth.public_stats.version";
    private static final String DAILY_USD_KEY = "dailyUsdtPerBaseline";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final PlatformConfigFacade config;
    private final UserOpsMapper users;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiResult<Map<String, Object>> overview() {
        Optional<Long> version = readVersion(false);
        Optional<Map<String, Object>> values = readValues();
        Optional<BigDecimal> dailyUsd = decimalConfig(DAILY_USD_KEY);
        if (version.isEmpty() || values.isEmpty() || dailyUsd.isEmpty()) {
            return ApiResult.fail(503, "H9_CONFIG_UNAVAILABLE");
        }
        String validationError = validateStored(values.get());
        if (validationError != null) {
            return ApiResult.fail(503, "H9_CONFIG_INVALID");
        }
        return ApiResult.ok(response(version.get(), values.get(), dailyUsd.get()));
    }

    /** Safe public projection. A missing/invalid aggregate remains unavailable rather than falling back. */
    public ApiResult<Map<String, Object>> publicProjection() {
        ApiResult<Map<String, Object>> result = overview();
        if (result.getCode() != 0) {
            return result;
        }
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("version", result.getData().get("version"));
        projection.put("values", result.getData().get("values"));
        projection.put("realUserCount", result.getData().get("realUserCount"));
        projection.put("effectiveAt", result.getData().get("effectiveAt"));
        return ApiResult.ok(projection);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> update(GrowthPublicStatsUpdateRequest request, String idempotencyKey) {
        String requestError = validateRequest(request, idempotencyKey);
        if (requestError != null) {
            return ApiResult.fail(422, requestError);
        }
        Optional<Long> lockedVersion = readVersion(true);
        if (lockedVersion.isEmpty()) {
            return ApiResult.fail(503, "H9_CONFIG_UNAVAILABLE");
        }
        if (!lockedVersion.get().equals(request.expectedVersion())) {
            return ApiResult.fail(409, "H9_CONFIG_VERSION_CONFLICT");
        }
        Optional<Map<String, Object>> beforeOptional = readValues();
        Optional<BigDecimal> dailyUsd = decimalConfig(DAILY_USD_KEY);
        if (beforeOptional.isEmpty() || dailyUsd.isEmpty() || validateStored(beforeOptional.get()) != null) {
            return ApiResult.fail(503, "H9_CONFIG_UNAVAILABLE");
        }

        Map<String, Object> before = beforeOptional.get();
        long now = clock.millis();
        long currentBase = longValue(before.get("registeredUsersBase"));
        long anchor = currentBase == request.registeredUsersBase()
                ? longValue(before.get("registeredUsersAnchorAt")) : now;
        Map<String, Object> nextValues = requestValues(request, anchor, now);
        long nextVersion = lockedVersion.get() + 1L;
        try {
            config.upsertAdminValue(VALUES_KEY, objectMapper.writeValueAsString(nextValues), "JSON", "growth",
                    "H9 public stats aggregate");
        } catch (Exception ex) {
            throw new IllegalStateException("H9_CONFIG_SERIALIZATION_FAILED", ex);
        }
        config.upsertAdminValue(VERSION_KEY, String.valueOf(nextVersion), "NUMBER", "growth",
                "H9 public stats aggregate version");
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("GROWTH_H9_PUBLIC_STATS_UPDATED")
                .resourceType("GROWTH_PUBLIC_STATS")
                .resourceId("H9")
                .actorUsername(AdminActorResolver.resolve(request.operator()))
                .riskLevel("HIGH")
                .detail(Map.of(
                        "before", before,
                        "after", nextValues,
                        "expectedVersion", request.expectedVersion(),
                        "newVersion", nextVersion,
                        "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey.trim()))
                .build());
        return ApiResult.ok(response(nextVersion, nextValues, dailyUsd.get()));
    }

    private Map<String, Object> response(long version, Map<String, Object> values, BigDecimal dailyUsd) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", version);
        data.put("values", values);
        data.put("defaults", defaultValues(longValue(values.get("registeredUsersAnchorAt"))));
        data.put("publishedDailyUsdPerDevice", dailyUsd);
        data.put("realUserCount", users.countUsers());
        data.put("effectiveAt", Instant.ofEpochMilli(longValue(values.get("effectiveAt"))).toString());
        return data;
    }

    private Map<String, Object> requestValues(GrowthPublicStatsUpdateRequest request, long anchor, long effectiveAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fleetDevices", request.fleetDevices());
        values.put("onlineRatePct", request.onlineRatePct());
        values.put("onlineJitter", request.onlineJitter());
        values.put("registeredUsersBase", request.registeredUsersBase());
        values.put("registeredUsersMonthlyGrowthPct", request.registeredUsersMonthlyGrowthPct());
        values.put("registeredUsersAnchorAt", anchor);
        values.put("effectiveAt", effectiveAt);
        values.put("virtualUserCount", request.virtualUserCount());
        List<Map<String, Object>> bands = new ArrayList<>();
        for (GrowthPublicStatsUpdateRequest.PercentileBand band : request.hashratePercentileTable()) {
            bands.add(Map.of("tops", band.tops(), "cumPct", band.cumPct()));
        }
        values.put("hashratePercentileTable", bands);
        return values;
    }

    private Map<String, Object> defaultValues(long anchor) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("fleetDevices", 28_432);
        defaults.put("onlineRatePct", new BigDecimal("100"));
        defaults.put("onlineJitter", 24);
        defaults.put("registeredUsersBase", 1_420_000L);
        defaults.put("registeredUsersMonthlyGrowthPct", new BigDecimal("2.9"));
        // The anchor is server-owned.  Returning the aggregate's current anchor keeps the
        // restore-default payload structurally complete; saving the default base will still
        // reset it transactionally in update().
        defaults.put("registeredUsersAnchorAt", anchor);
        defaults.put("virtualUserCount", 12_000L);
        defaults.put("hashratePercentileTable", List.of(
                band("5", "20"), band("20", "55"), band("60", "82"), band("150", "96"),
                band("700", "97.6"), band("2700", "98.7"), band("5400", "99.3"),
                band("11000", "99.6"), band("27000", "99.8"), band("53000", "100")));
        return defaults;
    }

    private Map<String, Object> band(String tops, String cumPct) {
        return Map.of("tops", new BigDecimal(tops), "cumPct", new BigDecimal(cumPct));
    }

    private String validateRequest(GrowthPublicStatsUpdateRequest request, String idempotencyKey) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || !StringUtils.hasText(request.reason()) || request.reason().trim().length() < 8
                || request.reason().trim().length() > 200 || !StringUtils.hasText(idempotencyKey)) {
            return "H9_REQUEST_INVALID";
        }
        if (!between(request.fleetDevices(), 1_000, 1_000_000)
                || !between(request.onlineRatePct(), "50", "100")
                || !between(request.onlineJitter(), 0, 500)
                || !between(request.registeredUsersBase(), 0L, 100_000_000L)
                || !between(request.registeredUsersMonthlyGrowthPct(), "0", "50")
                || !between(request.virtualUserCount(), 0L, 10_000_000L)) {
            return "H9_VALUES_OUT_OF_RANGE";
        }
        if (!validBands(request.hashratePercentileTable())) {
            return "H9_PERCENTILE_TABLE_INVALID";
        }
        return null;
    }

    private String validateStored(Map<String, Object> values) {
        try {
            if (!between(intValue(values.get("fleetDevices")), 1_000, 1_000_000)
                    || !between(decimalValue(values.get("onlineRatePct")), "50", "100")
                    || !between(intValue(values.get("onlineJitter")), 0, 500)
                    || !between(longValue(values.get("registeredUsersBase")), 0L, 100_000_000L)
                    || !between(decimalValue(values.get("registeredUsersMonthlyGrowthPct")), "0", "50")
                    || longValue(values.get("registeredUsersAnchorAt")) <= 0
                    || longValue(values.get("effectiveAt")) <= 0
                    || !between(longValue(values.get("virtualUserCount")), 0L, 10_000_000L)) {
                return "invalid";
            }
            Object rawBands = values.get("hashratePercentileTable");
            if (!(rawBands instanceof List<?> rows) || rows.size() < 2) {
                return "invalid";
            }
            BigDecimal previousTops = null;
            BigDecimal previousPct = null;
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) return "invalid";
                BigDecimal tops = decimalValue(map.get("tops"));
                BigDecimal pct = decimalValue(map.get("cumPct"));
                if (tops.signum() < 0 || pct.signum() < 0 || pct.compareTo(new BigDecimal("100")) > 0
                        || previousTops != null && tops.compareTo(previousTops) <= 0
                        || previousPct != null && pct.compareTo(previousPct) < 0) return "invalid";
                previousTops = tops;
                previousPct = pct;
            }
            return null;
        } catch (RuntimeException ex) {
            return "invalid";
        }
    }

    private boolean validBands(List<GrowthPublicStatsUpdateRequest.PercentileBand> bands) {
        if (bands == null || bands.size() < 2) return false;
        BigDecimal previousTops = null;
        BigDecimal previousPct = null;
        for (GrowthPublicStatsUpdateRequest.PercentileBand band : bands) {
            if (band == null || band.tops() == null || band.cumPct() == null
                    || band.tops().signum() < 0 || band.cumPct().signum() < 0
                    || band.cumPct().compareTo(new BigDecimal("100")) > 0
                    || previousTops != null && band.tops().compareTo(previousTops) <= 0
                    || previousPct != null && band.cumPct().compareTo(previousPct) < 0) return false;
            previousTops = band.tops();
            previousPct = band.cumPct();
        }
        return true;
    }

    private Optional<Long> readVersion(boolean lock) {
        try {
            Optional<String> raw = lock ? config.activeValueForUpdate(VERSION_KEY) : config.activeValue(VERSION_KEY);
            return raw.map(Long::parseLong).filter(value -> value >= 0);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> readValues() {
        try {
            return config.activeValue(VALUES_KEY).map(value -> {
                try {
                    return objectMapper.readValue(value, MAP_TYPE);
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> decimalConfig(String key) {
        try {
            return config.activeValue(key).map(BigDecimal::new);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private boolean between(Integer value, int min, int max) {
        return value != null && value >= min && value <= max;
    }

    private boolean between(Long value, long min, long max) {
        return value != null && value >= min && value <= max;
    }

    private boolean between(BigDecimal value, String min, String max) {
        return value != null && value.compareTo(new BigDecimal(min)) >= 0 && value.compareTo(new BigDecimal(max)) <= 0;
    }

    private int intValue(Object value) { return new BigDecimal(String.valueOf(value)).intValueExact(); }
    private long longValue(Object value) { return new BigDecimal(String.valueOf(value)).longValueExact(); }
    private BigDecimal decimalValue(Object value) { return new BigDecimal(String.valueOf(value)); }
}
