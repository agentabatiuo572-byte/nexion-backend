package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.GrowthPublicStatsUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class GrowthPublicStatsService {
    public static final String VALUES_KEY = "growth.public_stats.values";
    public static final String VERSION_KEY = "growth.public_stats.version";
    private static final List<String> HOME_FIELDS = List.of("homeOnlineCurrent", "homeOnlineStepMin", "homeOnlineStepMax",
            "homePaidCurrent", "homePaidStepMin", "homePaidStepMax");
    private static final String TICK_INTERVAL = "registeredUsersTickIntervalSec";
    private static final String TICK_STEP_MIN = "registeredUsersTickStepMin";
    private static final String TICK_STEP_MAX = "registeredUsersTickStepMax";
    /** Stored by the first cadence release (one fixed step); read as min = max. */
    private static final String LEGACY_TICK_STEP = "registeredUsersTickStep";
    private static final List<String> TICK_FIELDS = List.of(TICK_INTERVAL, TICK_STEP_MIN, TICK_STEP_MAX);
    /** Fields the App needs to replay the member estimate locally; published only once ticking is configured. */
    private static final List<String> MEMBER_ESTIMATE_FIELDS = List.of(
            "registeredUsersBase", "registeredUsersAnchorAt", TICK_INTERVAL, TICK_STEP_MIN, TICK_STEP_MAX);
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final String DAILY_USD_KEY = "dailyUsdtPerBaseline";
    private static final BigDecimal SANDBOX_DAILY_USD = new BigDecimal("0.06");
    private static final Set<String> SANDBOX_PROFILES = Set.of("test");
    private static final Set<String> PRODUCTION_PROFILES = Set.of("dev", "prod");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final PlatformConfigFacade config;
    private final UserOpsMapper users;
    private final DeviceOpsMapper deviceOps;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Environment environment;

    private final Optional<HomeDisplayService> homeDisplay;

    private record RuntimeScope(boolean sandbox, String runId, String versionKey, String valuesKey) { }

    public ApiResult<Map<String, Object>> overview() {
        String runtimeError = runtimeError();
        if (runtimeError != null) {
            return ApiResult.fail(503, runtimeError);
        }
        return overview(runtimeScope());
    }

    private ApiResult<Map<String, Object>> overview(RuntimeScope scope) {
        Optional<Long> version = readVersion(scope, false);
        Optional<Map<String, Object>> values = readValues(scope);
        Optional<BigDecimal> dailyUsd = scope.sandbox()
                ? Optional.of(SANDBOX_DAILY_USD) : decimalConfig(DAILY_USD_KEY);
        if (version.isEmpty() || values.isEmpty() || dailyUsd.isEmpty()) {
            return ApiResult.fail(503, "H9_CONFIG_UNAVAILABLE");
        }
        String validationError = validateStored(values.get());
        if (validationError != null) {
            return ApiResult.fail(503, "H9_CONFIG_INVALID");
        }
        long realUserCount = scope.sandbox() ? 0L : users.countUsers();
        return ApiResult.ok(response(version.get(), values.get(), dailyUsd.get(), realUserCount));
    }

    /**
     * Safe public projection. A missing/invalid aggregate remains unavailable rather than falling back.
     *
     * <p><b>真实聚合(zentao #59)。</b>对外页面此前只能拿到运营手填的
     * {@code fleetDevices} / {@code registeredUsersBase} / 月增长率,再由它们派生「日支付额」
     * 「每秒支付流」「本月新增」等**事实性**指标 —— 等于把人工配置的规模当成实测数据对外发布,
     * 并叠加人为抖动让数字「看起来有呼吸感」。这一节把可核验的服务端事实单独下发:
     * {@code verified} 里的每个数都来自真实表聚合,并自带口径与统计时刻;消费方必须优先读它,
     * 不得再拿配置值派生「已付 / 收入 / 在线」这类事实表述。
     *
     * <p>完整运营参数仍只留在管理接口。networkPulse 是独立的首页展示投影，
     * 复用 H9 用户增长配置与 HomeDisplay 在线批次，不改变 verified 或个人排名。
     */
    public ApiResult<Map<String, Object>> publicProjection() {
        String runtimeError = runtimeError();
        if (runtimeError != null) {
            return ApiResult.fail(503, runtimeError);
        }
        RuntimeScope scope = runtimeScope();
        ApiResult<Map<String, Object>> result = overview(scope);
        if (result.getCode() != 0) {
            return result;
        }
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("serverCanonical", true);
        projection.put("source", scope.sandbox() ? "mock" : "server:nx_config_item,nx_user");
        projection.put("sourceEnvironment", scope.sandbox() ? "SANDBOX" : "PRODUCTION");
        projection.put("runId", scope.runId());
        projection.put("version", result.getData().get("version"));
        projection.put("provenance", Map.of(
                "verified", scope.sandbox() ? "SANDBOX_UNAVAILABLE" : "SERVER_AGGREGATE"));
        // 复用 overview 已经算好的真实账号数:同一请求里重复聚合没有意义,
        // 也让「本次投影的所有数字来自同一次读取」这个不变量成立。
        projection.put("verified", verifiedAggregates(scope, result.getData().get("realUserCount")));
        projection.put("realUserCount", result.getData().get("realUserCount"));
        Map<?, ?> values = (Map<?, ?>) result.getData().get("values");
        if (hasMemberTick(values)) {
            // Only the four estimate inputs leave the admin API; the App replays the same
            // start value + interval × step formula so the number ticks between reads.
            MEMBER_ESTIMATE_FIELDS.forEach(key -> projection.put(key, values.get(key)));
        }
        projection.put("networkPulse", networkPulse(scope, result.getData()));
        return ApiResult.ok(projection);
    }

    private Map<String, Object> networkPulse(RuntimeScope scope, Map<String, Object> overview) {
        Map<String, Object> pulse = new LinkedHashMap<>();
        Map<?, ?> values = (Map<?, ?>) overview.get("values");
        pulse.put("registeredUsers", hasMemberTick(values) ? tickedMembers(values) : compoundedMembers(values));
        pulse.put("onlineDevices", null);
        if (!scope.sandbox() && homeDisplay.isPresent()) {
            try {
                Map<String, Object> batch = homeDisplay.get().current();
                long[] online = (long[]) batch.get("online");
                int index = (int) Math.min(online.length - 1,
                        Math.max(0L, (longValue(batch.get("serverNow")) - longValue(batch.get("startAt"))) / 1000));
                pulse.put("onlineDevices", online[index]);
            } catch (RuntimeException ex) {
                log.warn("Home display snapshot unavailable for network pulse", ex);
            }
        }
        return pulse;
    }

    /** Legacy model kept for aggregates saved before ticking existed: base compounded monthly from the anchor. */
    private Long compoundedMembers(Map<?, ?> values) {
        double months = Math.max(0, (clock.millis() - longValue(values.get("registeredUsersAnchorAt")))
                / (30d * 24 * 3_600_000));
        double registered = longValue(values.get("registeredUsersBase")) * Math.pow(
                1 + decimalValue(values.get("registeredUsersMonthlyGrowthPct")).doubleValue() / 100, months);
        return Double.isFinite(registered) && registered <= MAX_SAFE_INTEGER ? (long) Math.floor(registered) : null;
    }

    /**
     * Members after n ticks: start + floor(n × (min + max) / 2) + offset(n), offset(0) = 0.
     * offset(n) is a seeded hash in [0, spread] with spread = min(floor(mid) − min, max − ceil(mid)),
     * so every tick adds a pseudo-random amount within [min, max], the long-run average is the
     * midpoint, and any n is O(1). The App's derivedRegisteredUsers implements the same function
     * bit for bit (32-bit wrapping multiply, unsigned shifts), so all devices and refreshes agree.
     */
    static Long tickedMembers(long base, long anchorAt, long intervalSec, long stepMin, long stepMax, long nowMs) {
        long ticks = Math.max(0L, nowMs - anchorAt) / (intervalSec * 1000L);
        long sum = stepMin + stepMax;
        long spread = Math.min(sum / 2 - stepMin, stepMax - (sum + 1) / 2);
        if (sum != 0 && ticks > (MAX_SAFE_INTEGER - base - spread) / sum * 2) {
            return null;
        }
        return base + ticks * sum / 2 + (ticks == 0 || spread == 0 ? 0 : tickOffset(anchorAt, ticks, spread));
    }

    static long tickOffset(long anchorAt, long tick, long spread) {
        int h = (int) (anchorAt & 0xFFFFFFFFL) ^ ((int) tick * 0x9E3779B1);
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return Integer.toUnsignedLong(h) % (spread + 1);
    }

    private Long tickedMembers(Map<?, ?> values) {
        return tickedMembers(longValue(values.get("registeredUsersBase")), longValue(values.get("registeredUsersAnchorAt")),
                longValue(values.get(TICK_INTERVAL)), longValue(values.get(TICK_STEP_MIN)),
                longValue(values.get(TICK_STEP_MAX)), clock.millis());
    }

    private static boolean hasMemberTick(Map<?, ?> values) {
        return TICK_FIELDS.stream().allMatch(key -> values.get(key) != null);
    }

    /**
     * 可核验事实聚合。沙箱环境没有真实数据,所以整体缺席(null)——「不知道」不能变成 0,
     * 也不能退回运营配置值:那正是本缺陷要消除的混淆。
     */
    private Map<String, Object> verifiedAggregates(RuntimeScope scope, Object realUserCount) {
        if (scope.sandbox()) {
            return null;
        }
        Map<String, Object> verified = new LinkedHashMap<>();
        // 每个数都标注自己的口径与统计时刻,消费方无需再猜「这是实时还是推算」。
        verified.put("activeAccounts", aggregate(users.countActiveUsers(), "nx_user.status=ACTIVE", "COUNT"));
        // 注册账号数复用 overview 已读到的值,口径与 realUserCount 完全一致。
        verified.put("registeredAccounts", aggregate(longValue(realUserCount), "nx_user.is_deleted=0", "COUNT"));
        verified.put("installedDevices", aggregate(deviceOps.countTotalDevices(), "nx_user_device.is_deleted=0", "COUNT"));
        verified.put("onlineDevices", aggregate(deviceOps.countOnlineDevices(),
                "nx_user_device OWNED+ACTIVATED 且 runtime.online_status=ONLINE、有效心跳在服务端最近10分钟内", "COUNT"));
        verified.put("completedPayoutUsdt", aggregate(deviceOps.completedWithdrawalTotal(),
                "nx_withdrawal_order.status=COMPLETED 的 amount 合计", "SUM_USDT"));
        verified.put("capturedAt", Instant.ofEpochMilli(clock.millis()).toString());
        return verified;
    }

    private Map<String, Object> aggregate(long value, String definition, String kind) {
        return Map.of("value", value, "definition", definition, "kind", kind);
    }

    /** 金额聚合:保留 6 位小数,避免把 USDT 精度截断成整数。 */
    private Map<String, Object> aggregate(BigDecimal value, String definition, String kind) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        return Map.of("value", amount, "definition", definition, "kind", kind);
    }

    private String runtimeError() {
        String[] profiles = environment.getActiveProfiles();
        boolean sandbox = isStrictProfile(profiles, SANDBOX_PROFILES, false);
        boolean production = isStrictProfile(profiles, PRODUCTION_PROFILES, true);
        if (!sandbox && !production) {
            return "H9_PROFILE_INVALID";
        }
        if (sandbox && !acceptanceRunId().matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}")) {
            return "H9_SANDBOX_RUN_ID_REQUIRED";
        }
        return null;
    }

    private RuntimeScope runtimeScope() {
        boolean sandbox = isStrictProfile(environment.getActiveProfiles(), SANDBOX_PROFILES, false);
        String runId = sandbox ? acceptanceRunId() : "";
        return sandbox
                ? new RuntimeScope(true, runId, "h9.sb." + runId + ".v", "h9.sb." + runId + ".data")
                : new RuntimeScope(false, "", VERSION_KEY, VALUES_KEY);
    }

    private String acceptanceRunId() {
        return environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
    }

    private static boolean isStrictProfile(String[] activeProfiles, Set<String> allowed, boolean allowEmpty) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return allowEmpty;
        }
        return activeProfiles.length == 1
                && activeProfiles[0] != null
                && allowed.contains(activeProfiles[0].trim().toLowerCase(Locale.ROOT));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> update(GrowthPublicStatsUpdateRequest request, String idempotencyKey) {
        String requestError = validateRequest(request, idempotencyKey);
        if (requestError != null) {
            return ApiResult.fail(422, requestError);
        }
        String runtimeError = runtimeError();
        if (runtimeError != null) {
            return ApiResult.fail(503, runtimeError);
        }
        RuntimeScope scope = runtimeScope();
        Optional<Long> lockedVersion = readVersion(scope, true);
        if (lockedVersion.isEmpty()) {
            return ApiResult.fail(503, "H9_CONFIG_UNAVAILABLE");
        }
        if (!lockedVersion.get().equals(request.expectedVersion())) {
            return ApiResult.fail(409, "H9_CONFIG_VERSION_CONFLICT");
        }
        Optional<Map<String, Object>> beforeOptional = readValues(scope);
        Optional<BigDecimal> dailyUsd = scope.sandbox()
                ? Optional.of(SANDBOX_DAILY_USD) : decimalConfig(DAILY_USD_KEY);
        if (beforeOptional.isEmpty() || dailyUsd.isEmpty() || validateStored(beforeOptional.get()) != null) {
            return ApiResult.fail(503, "H9_CONFIG_UNAVAILABLE");
        }

        Map<String, Object> before = beforeOptional.get();
        long now = clock.millis();
        long currentBase = longValue(before.get("registeredUsersBase"));
        // Changing the start value or the tick cadence restarts the estimate from the start value.
        boolean memberTickChanged = request.registeredUsersTickIntervalSec() != null
                && (!request.registeredUsersTickIntervalSec().equals(nullableInt(before.get(TICK_INTERVAL)))
                || !request.registeredUsersTickStepMin().equals(nullableLong(before.get(TICK_STEP_MIN)))
                || !request.registeredUsersTickStepMax().equals(nullableLong(before.get(TICK_STEP_MAX))));
        long anchor = currentBase == request.registeredUsersBase() && !memberTickChanged
                ? longValue(before.get("registeredUsersAnchorAt")) : now;
        Map<String, Object> nextValues = requestValues(request, anchor, now, before);
        if (!scope.sandbox()) homeDisplay.ifPresent(display -> display.configure(before, nextValues, now));
        long nextVersion = lockedVersion.get() + 1L;
        try {
            config.upsertAdminValue(scope.valuesKey(), objectMapper.writeValueAsString(nextValues), "JSON",
                    scope.sandbox() ? "growth_sandbox" : "growth", "H9 public stats aggregate");
        } catch (Exception ex) {
            throw new IllegalStateException("H9_CONFIG_SERIALIZATION_FAILED", ex);
        }
        config.upsertAdminValue(scope.versionKey(), String.valueOf(nextVersion), "NUMBER",
                scope.sandbox() ? "growth_sandbox" : "growth", "H9 public stats aggregate version");
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("GROWTH_H9_PUBLIC_STATS_UPDATED")
                .resourceType("GROWTH_PUBLIC_STATS")
                .resourceId(scope.sandbox() ? "H9:" + scope.runId() : "H9")
                .actorUsername(AdminActorResolver.resolve(request.operator()))
                .riskLevel("HIGH")
                .detail(Map.of(
                        "before", before,
                        "after", nextValues,
                        "expectedVersion", request.expectedVersion(),
                        "newVersion", nextVersion,
                        "sourceEnvironment", scope.sandbox() ? "SANDBOX" : "PRODUCTION",
                        "runId", scope.runId(),
                        "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey.trim()))
                .build());
        return ApiResult.ok(response(nextVersion, nextValues, dailyUsd.get(), scope.sandbox() ? 0L : users.countUsers()));
    }

    private Map<String, Object> response(
            long version, Map<String, Object> values, BigDecimal dailyUsd, long realUserCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", version);
        data.put("values", values);
        data.put("defaults", defaultValues(longValue(values.get("registeredUsersAnchorAt"))));
        data.put("publishedDailyUsdPerDevice", dailyUsd);
        data.put("realUserCount", realUserCount);
        data.put("effectiveAt", Instant.ofEpochMilli(longValue(values.get("effectiveAt"))).toString());
        return data;
    }

    private Map<String, Object> requestValues(GrowthPublicStatsUpdateRequest request, long anchor, long effectiveAt, Map<String, Object> before) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fleetDevices", request.fleetDevices());
        values.put("onlineRatePct", request.onlineRatePct());
        values.put("onlineJitter", request.onlineJitter());
        values.put("registeredUsersBase", request.registeredUsersBase());
        values.put("registeredUsersMonthlyGrowthPct", request.registeredUsersMonthlyGrowthPct());
        values.put("registeredUsersAnchorAt", anchor);
        // Older PC clients omit the cadence; keep whatever is stored (absent stays unpublished).
        if (request.registeredUsersTickIntervalSec() == null) {
            if (hasMemberTick(before)) TICK_FIELDS.forEach(key -> values.put(key, before.get(key)));
        } else {
            values.put(TICK_INTERVAL, request.registeredUsersTickIntervalSec());
            values.put(TICK_STEP_MIN, request.registeredUsersTickStepMin());
            values.put(TICK_STEP_MAX, request.registeredUsersTickStepMax());
        }
        values.put("effectiveAt", effectiveAt);
        values.put("virtualUserCount", request.virtualUserCount());
        List<Map<String, Object>> bands = new ArrayList<>();
        for (GrowthPublicStatsUpdateRequest.PercentileBand band : request.hashratePercentileTable()) {
            bands.add(Map.of("tops", band.tops(), "cumPct", band.cumPct()));
        }
        values.put("hashratePercentileTable", bands);
        Object[] requested = { request.homeOnlineCurrent(), request.homeOnlineStepMin(), request.homeOnlineStepMax(),
                request.homePaidCurrent(), request.homePaidStepMin(), request.homePaidStepMax() };
        boolean changed = false;
        for (int i = 0; i < HOME_FIELDS.size(); i++) {
            String key = HOME_FIELDS.get(i);
            Object next = requested[i] == null ? before.get(key) : requested[i];
            values.put(key, next);
            changed |= decimalValue(next).compareTo(decimalValue(before.get(key))) != 0;
        }
        values.put("homeDisplayEnabled", request.homeDisplayEnabled() == null ? before.get("homeDisplayEnabled") : request.homeDisplayEnabled());
        values.put("homeDisplayAnchorAt", changed ? effectiveAt : before.get("homeDisplayAnchorAt"));
        return values;
    }

    private Map<String, Object> defaultValues(long anchor) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("fleetDevices", 28_432);
        defaults.put("onlineRatePct", new BigDecimal("100"));
        defaults.put("onlineJitter", 24);
        defaults.put("registeredUsersBase", 1_420_000L);
        defaults.put("registeredUsersMonthlyGrowthPct", new BigDecimal("2.9"));
        defaults.put(TICK_INTERVAL, 60);
        defaults.put(TICK_STEP_MIN, 3L);
        defaults.put(TICK_STEP_MAX, 10L);
        // The anchor is server-owned.  Returning the aggregate's current anchor keeps the
        // restore-default payload structurally complete; saving the default base will still
        // reset it transactionally in update().
        defaults.put("registeredUsersAnchorAt", anchor);
        defaults.put("virtualUserCount", 12_000L);
        defaults.put("hashratePercentileTable", List.of(
                band("5", "20"), band("20", "55"), band("60", "82"), band("150", "96"),
                band("700", "97.6"), band("2700", "98.7"), band("5400", "99.3"),
                band("11000", "99.6"), band("27000", "99.8"), band("53000", "100")));
        HOME_FIELDS.forEach(key -> defaults.put(key, 0));
        defaults.put("homeDisplayAnchorAt", anchor);
        defaults.put("homeDisplayEnabled", false);
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
        Object[] home = { request.homeOnlineCurrent(), request.homeOnlineStepMin(), request.homeOnlineStepMax(),
                request.homePaidCurrent(), request.homePaidStepMin(), request.homePaidStepMax() };
        long supplied = java.util.Arrays.stream(home).filter(java.util.Objects::nonNull).count();
        if (supplied != 0 && (supplied != HOME_FIELDS.size() || !validHomeDisplay(home))) return "H9_HOME_DISPLAY_INVALID";
        long tickSupplied = java.util.stream.Stream.of(request.registeredUsersTickIntervalSec(),
                request.registeredUsersTickStepMin(), request.registeredUsersTickStepMax()).filter(java.util.Objects::nonNull).count();
        if (tickSupplied != 0 && (tickSupplied != TICK_FIELDS.size() || !validMemberTick(request.registeredUsersTickIntervalSec(),
                request.registeredUsersTickStepMin(), request.registeredUsersTickStepMax()))) {
            return "H9_MEMBER_TICK_INVALID";
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
            if (!(values.get("homeDisplayEnabled") instanceof Boolean)) return "invalid";
            long tickStored = TICK_FIELDS.stream().filter(key -> values.get(key) != null).count();
            if (tickStored != 0 && (tickStored != TICK_FIELDS.size() || !validMemberTick(intValue(values.get(TICK_INTERVAL)),
                    longValue(values.get(TICK_STEP_MIN)), longValue(values.get(TICK_STEP_MAX))))) {
                return "invalid";
            }
            if (!validHomeDisplay(HOME_FIELDS.stream().map(values::get).toArray())
                    || longValue(values.get("homeDisplayAnchorAt")) <= 0) return "invalid";
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

    private Optional<Long> readVersion(RuntimeScope scope, boolean lock) {
        try {
            Optional<String> raw = lock
                    ? config.activeValueForUpdate(scope.versionKey()) : config.activeValue(scope.versionKey());
            return raw.map(Long::parseLong).filter(value -> value >= 0);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> readValues(RuntimeScope scope) {
        try {
            return config.activeValue(scope.valuesKey()).map(value -> {
                try {
                    Map<String, Object> stored = objectMapper.readValue(value, MAP_TYPE);
                    // Older H9 records retain their timestamp and start with a static display until configured.
                    stored.putIfAbsent("homeOnlineCurrent", Math.round(decimalValue(stored.get("fleetDevices")).doubleValue()
                            * decimalValue(stored.get("onlineRatePct")).doubleValue() / 100));
                    HOME_FIELDS.forEach(key -> stored.putIfAbsent(key, 0));
                    stored.putIfAbsent("homeDisplayAnchorAt", stored.get("effectiveAt"));
                    stored.putIfAbsent("homeDisplayEnabled", false);
                    Object legacyStep = stored.remove(LEGACY_TICK_STEP);
                    if (legacyStep != null) {
                        stored.putIfAbsent(TICK_STEP_MIN, legacyStep);
                        stored.putIfAbsent(TICK_STEP_MAX, legacyStep);
                    }
                    return stored;
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private boolean validHomeDisplay(Object[] values) {
        try {
            BigDecimal[] n = java.util.Arrays.stream(values).map(this::decimalValue).toArray(BigDecimal[]::new);
            for (int i = 0; i < n.length; i++) {
                BigDecimal limit = new BigDecimal(i == 3 || i == 0 ? "9000000000000" : "1000000");
                if (n[i].signum() < 0 || n[i].compareTo(limit) > 0 || n[i].stripTrailingZeros().scale() > (i < 3 ? 0 : 2)) return false;
            }
            return n[1].compareTo(n[2]) <= 0 && n[4].compareTo(n[5]) <= 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Same legal domain as the App's publicStatsHealth: 1 s – 1 day per tick, 0 ≤ min ≤ max ≤ 100,000,000 members per tick. */
    private boolean validMemberTick(Integer intervalSec, Long stepMin, Long stepMax) {
        return between(intervalSec, 1, 86_400) && between(stepMin, 0L, 100_000_000L)
                && between(stepMax, 0L, 100_000_000L) && stepMin <= stepMax;
    }

    private Integer nullableInt(Object value) { return value == null ? null : intValue(value); }
    private Long nullableLong(Object value) { return value == null ? null : longValue(value); }

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
