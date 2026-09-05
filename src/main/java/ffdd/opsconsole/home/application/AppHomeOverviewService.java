package ffdd.opsconsole.home.application;

import ffdd.opsconsole.device.application.ComputeTaskProofVerifier;
import ffdd.opsconsole.growth.application.GrowthPublicStatsService;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.EarningsLedgerRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.MarketProductRow;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.MarketTaskRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.OnGridClientRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.OwnedDeviceRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.PeriodRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.PromoRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.TaskPriceHistoryRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-only projection. Missing production facts are represented as null/empty collections. */
@Service
@RequiredArgsConstructor
public class AppHomeOverviewService {
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SANDBOX_PROFILES = Set.of("test");
    private static final Set<String> PRODUCTION_PROFILES = Set.of("dev", "prod");
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private static final List<String> RUNTIME_CANDIDATE_RESOURCES = List.of(
            "ffdd/opsconsole/home/application/AppHomeOverviewService.class",
            "ffdd/opsconsole/home/mapper/AppHomeOverviewMapper.class",
            "ffdd/opsconsole/device/application/AppNetworkRankService.class",
            "ffdd/opsconsole/device/mapper/AppNetworkRankMapper.class",
            "ffdd/opsconsole/device/application/AppTaskAssignmentService.class",
            "ffdd/opsconsole/device/mapper/AppTaskAssignmentMapper.class");
    private static final String LEGACY_UNSCOPED_RUN_ID = "LEGACY_UNSCOPED";
    private static final String PRODUCTION_SOURCE =
            "server:nx_compute_receipt,nx_compute_task,nx_user_device,nx_compute_datacenter,nx_product,nx_growth_promo_banner";
    private static final String SANDBOX_SOURCE =
            "server:sandbox-run-projection:nx_config_item,nx_admin_device_task,nx_product";
    private static final List<String> DEVICE_UPGRADE_LADDER = List.of(
            "phone", "stellarbox-s1", "stellarbox-pro", "stellarbox-pro-v2",
            "stellarrack-p1", "stellarrack-p2");
    private final AppHomeOverviewMapper mapper;
    private final ComputeTaskProofVerifier proofVerifier;
    private final GrowthPublicStatsService publicStatsService;
    private final AppCanonicalBoundaryService purchaseEligibilityService;
    private final Clock clock;
    private final Environment runtimeEnvironment;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> overview(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        boolean isolatedProfile = isStrictProfile(runtimeEnvironment.getActiveProfiles(), SANDBOX_PROFILES, false);
        boolean productionProfile = isStrictProfile(runtimeEnvironment.getActiveProfiles(), PRODUCTION_PROFILES, true);
        if (!isolatedProfile && !productionProfile) {
            return ApiResult.fail(503, "APP_HOME_PROFILE_INVALID");
        }
        // dev and prod expose the same canonical production-shaped home contract.
        // The compute proof rail may still be simulated in local development,
        // but it must not redirect persisted homepage reads to Sandbox tables.
        String sourceEnvironment = productionProfile ? "PRODUCTION" : proofVerifier.sourceEnvironment();
        boolean sandbox = isolatedProfile;
        boolean accountSandbox = sandbox;
        AppHomeOverviewMapper.UserEnvironmentRow accountEnvironment = mapper.userEnvironment(userId);
        if (accountEnvironment == null || accountEnvironment.sandbox() != accountSandbox) {
            return ApiResult.fail(403, "USER_ENVIRONMENT_MISMATCH");
        }
        try {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), SERVER_ZONE);
            LocalDate today = now.toLocalDate();
            LocalDateTime dayStart = today.atStartOfDay();
            LocalDateTime weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L).atStartOfDay();
            LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
            if (sandbox) {
                String runId = acceptanceRunId();
                if (!validSandboxRunId(runId)) {
                    return ApiResult.fail(503, "APP_HOME_SANDBOX_RUN_ID_REQUIRED");
                }
                return sandboxOverview(userId, runId, now, dayStart, weekStart, monthStart);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("serverCanonical", true);
            result.put("sourceEnvironment", "PRODUCTION");
            result.put("runId", "");
            result.put("generatedAt", clock.instant().toString());
            result.put("accountScope", "authenticated-account");
            Map<String, Object> earnings = new LinkedHashMap<>();
            AppHomeOverviewMapper.EarningsSummaryRow summary = mapper.earningsSummary(
                    userId, sourceEnvironment, dayStart, dayStart.minusDays(1), now.minusDays(1),
                    weekStart, monthStart, now);
            PeriodRow todayEarnings = summary == null ? null : summary.today();
            PeriodRow yesterdaySameTimeEarnings = summary == null ? null : summary.yesterday();
            earnings.put("today", period(todayEarnings));
            earnings.put("todayVsYesterdayPct", percentageChange(todayEarnings, yesterdaySameTimeEarnings));
            earnings.put("week", period(summary == null ? null : summary.week()));
            earnings.put("month", period(summary == null ? null : summary.month()));
            earnings.put("all", period(summary == null ? null : summary.all()));
            result.put("earnings", earnings);
            result.put("earningsLedgerMode", "SETTLED");
            result.put("earningsLedger", earningsLedger(mapper.earningsLedger(userId, sourceEnvironment)));
            List<MarketProductRow> marketProducts = safe(mapper.marketProducts());
            result.put("marketBoard", marketBoard(
                    safe(mapper.marketTasks()), safe(mapper.marketTaskPriceHistory()), marketProducts));
            result.put("doTheMath", doTheMath(
                    userId, mapper.highestActiveDevice(userId, accountSandbox), marketProducts));
            result.put("weeklyPromo", promo());
            AppHomeOverviewMapper.PaidSummary paid = mapper.cumulativePaid(userId, accountSandbox);
            Long activeDevices = mapper.activeDevices(userId, accountSandbox);
            result.put("onboarding", linked("cumulativePaidUsdt", fact(paid == null ? null : paid.amountUsdt(), paid == null ? null : paid.orderCount()),
                    "activeDevices", activeDevices));
            AppHomeOverviewMapper.OnGridSummary grid = mapper.onGrid(sourceEnvironment, accountSandbox);
            result.put("onGrid", linked("clients", clients(mapper.onGridClients(accountSandbox)),
                    "activeDevices", mapper.globalActiveDevices(accountSandbox),
                    "activeJobs", fact(grid == null ? null : grid.activeJobs(), grid == null ? null : grid.activeJobs()),
                    "perSecUsdt", fact(grid == null ? null : grid.perSecUsdt(), grid == null ? null : grid.activeJobs())));
            result.put("source", PRODUCTION_SOURCE);
            return ApiResult.ok(result);
        } catch (DataAccessException ex) {
            return ApiResult.fail(503, "APP_HOME_OVERVIEW_UNAVAILABLE");
        }
    }

    private ApiResult<Map<String, Object>> sandboxOverview(Long userId, String runId, LocalDateTime now,
                                                           LocalDateTime dayStart, LocalDateTime weekStart,
                                                           LocalDateTime monthStart) {
        ApiResult<Map<String, Object>> publicStats = publicStatsService.overview();
        if (publicStats.getCode() != 0 || publicStats.getData() == null) {
            return ApiResult.fail(503, "APP_HOME_NETWORK_STATS_UNAVAILABLE");
        }
        Map<String, Object> values = map(publicStats.getData().get("values"));
        Long fleetDevices = whole(values == null ? null : values.get("fleetDevices"));
        BigDecimal onlineRatePct = decimal(values == null ? null : values.get("onlineRatePct"));
        BigDecimal dailyUsd = decimal(publicStats.getData().get("publishedDailyUsdPerDevice"));
        if (fleetDevices == null || fleetDevices < 0 || onlineRatePct == null
                || onlineRatePct.signum() < 0 || onlineRatePct.compareTo(new BigDecimal("100")) > 0
                || dailyUsd == null || dailyUsd.signum() < 0) {
            return ApiResult.fail(503, "APP_HOME_NETWORK_STATS_INVALID");
        }
        long onlineDevices = BigDecimal.valueOf(fleetDevices).multiply(onlineRatePct)
                .divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP).longValue();
        BigDecimal perSecUsdt = dailyUsd.multiply(BigDecimal.valueOf(onlineDevices))
                .divide(new BigDecimal("86400"), 8, RoundingMode.HALF_UP);
        List<MarketTaskRow> tasks = safe(mapper.marketTasks());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverCanonical", true);
        result.put("sourceEnvironment", "SANDBOX");
        result.put("runId", runId);
        result.put("generatedAt", clock.instant().toString());
        result.put("accountScope", "authenticated-account");
        Map<String, Object> earnings = new LinkedHashMap<>();
        earnings.put("today", period(null));
        earnings.put("todayVsYesterdayPct", null);
        earnings.put("week", period(null));
        earnings.put("month", period(null));
        earnings.put("all", period(null));
        result.put("earnings", earnings);
        result.put("earningsLedgerMode", "SANDBOX_QUOTE_EXAMPLES");
        result.put("earningsLedger", sandboxLedger(tasks, runId, now));
        result.put("marketBoard", marketBoard(tasks, List.of(), safe(mapper.marketProducts())));
        result.put("doTheMath", null);
        result.put("weeklyPromo", null);
        result.put("onboarding", linked("cumulativePaidUsdt", null,
                "activeDevices", mapper.sandboxActiveDevices(userId, runId)));
        result.put("onGrid", linked("clients", sandboxClients(tasks, onlineDevices),
                "activeDevices", onlineDevices,
                "activeJobs", (long) uniqueTaskCodes(tasks).size(),
                "perSecUsdt", perSecUsdt));
        result.put("source", SANDBOX_SOURCE);
        String candidateId = runtimeCandidateId();
        if (candidateId != null) result.put("serverCandidateId", candidateId);
        return ApiResult.ok(result);
    }

    /**
     * Fingerprint the exact feature classes loaded by this JVM. The E2E probe
     * independently hashes the same target/classes resources, so an injected
     * environment label cannot make an old backend look like the tested build.
     */
    private String runtimeCandidateId() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ClassLoader loader = AppHomeOverviewService.class.getClassLoader();
            for (String resource : RUNTIME_CANDIDATE_RESOURCES) {
                digest.update(resource.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream input = loader.getResourceAsStream(resource)) {
                    if (input == null) return null;
                    digest.update(input.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            return null;
        }
    }

    private Map<String, Object> marketBoard(List<MarketTaskRow> tasks, List<TaskPriceHistoryRow> history,
                                            List<MarketProductRow> products) {
        List<Map<String, Object>> workloads = new ArrayList<>();
        List<Map<String, Object>> rankings = new ArrayList<>();
        Set<String> workloadCodes = new LinkedHashSet<>();
        Map<String, List<TaskPriceHistoryRow>> historyByTask = new LinkedHashMap<>();
        for (TaskPriceHistoryRow row : history) {
            if (row == null || row.taskId() == null || row.price() == null || row.observedAt() == null
                    || row.price().signum() < 0) {
                continue;
            }
            historyByTask.computeIfAbsent(row.taskId(), ignored -> new ArrayList<>()).add(row);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), SERVER_ZONE);
        if (tasks != null) for (MarketTaskRow row : tasks) {
                String code = taskCode(row.taskClass());
                if (code == null || !workloadCodes.add(code)) continue;
                PriceHistoryProjection projection = priceHistory(historyByTask.get(row.taskId()), now);
                workloads.add(linked("code", code, "name", text(row.name()), "unit", text(row.unit()),
                        "price", nonNegative(row.price()),
                        "deltaPct", projection.deltaPct(),
                        "sparkline", projection.sparkline(),
                        "flagshipDeltaPct", null));
        }
        if (products != null) {
            int rank = 1;
            for (MarketProductRow row : products) {
                rankings.add(linked("rank", rank++, "name", text(row.name()), "kind", deviceKind(row),
                        "bestFor", null, "dailyUsdt", nonNegative(row.dailyUsdt())));
            }
        }
        return linked("workloads", workloads, "deviceRankings", rankings);
    }

    private Map<String, Object> doTheMath(Long userId, OwnedDeviceRow base, List<MarketProductRow> products) {
        if (base == null || base.dailyUsdt() == null || base.dailyUsdt().signum() <= 0) return null;
        String baseKind = productKind(String.join(" ",
                text(base.productCode()), text(base.productTier()), text(base.deviceType()), text(base.name())));
        int baseIndex = DEVICE_UPGRADE_LADDER.indexOf(baseKind);
        if (baseIndex == DEVICE_UPGRADE_LADDER.size() - 1) return null;
        List<MarketProductRow> candidates = products.stream()
                .filter(row -> row != null && row.dailyUsdt() != null && row.dailyUsdt().compareTo(base.dailyUsdt()) > 0)
                .filter(row -> row.priceUsdt() != null && row.priceUsdt().signum() > 0)
                // A stop-loss CTA must be actionable now, so finite inventory must be positive.
                .filter(row -> ProductInventoryMode.isUnlimited(row.inventoryMode())
                        || (row.stock() != null && row.stock() > 0))
                .filter(row -> !text(row.productNo()).isBlank() && !text(row.name()).isBlank())
                .filter(row -> {
                    int targetIndex = DEVICE_UPGRADE_LADDER.indexOf(deviceKind(row));
                    return targetIndex > baseIndex;
                })
                .sorted(java.util.Comparator
                        .comparingInt((MarketProductRow row) -> DEVICE_UPGRADE_LADDER.indexOf(deviceKind(row)))
                        .thenComparing(MarketProductRow::dailyUsdt)
                        .thenComparing(MarketProductRow::productNo))
                .toList();
        if (candidates.isEmpty()) return null;
        MarketProductRow target = immediatelyPurchasable(userId, candidates);
        if (target == null) return null;
        String targetKind = deviceKind(target);
        long multiplier = target.dailyUsdt().divide(base.dailyUsdt(), 0, RoundingMode.HALF_UP).longValue();
        long paybackDays = target.priceUsdt().divide(target.dailyUsdt(), 0, RoundingMode.HALF_UP).longValue();
        if (multiplier < 1 || paybackDays < 1) return null;
        return linked(
                "basis", "OWNED_DEVICE_TO_NEXT_CATALOG_PRODUCT",
                "base", linked("kind", baseKind, "name", text(base.name()),
                        "dailyUsdt", nonNegative(base.dailyUsdt())),
                "target", linked("productNo", text(target.productNo()), "kind", targetKind,
                        "name", text(target.name()), "dailyUsdt", nonNegative(target.dailyUsdt()),
                        "priceUsdt", nonNegative(target.priceUsdt())),
                "multiplier", multiplier,
                "paybackDays", paybackDays);
    }

    private MarketProductRow immediatelyPurchasable(Long userId, List<MarketProductRow> candidates) {
        try {
            List<String> productNos = candidates.stream().map(MarketProductRow::productNo).toList();
            ApiResult<Map<String, AppCanonicalBoundaryService.PurchaseEligibilityDecision>> result =
                    purchaseEligibilityService.purchaseEligibilityBatch(userId, productNos);
            if (result == null || result.getCode() != 0 || result.getData() == null) return null;
            Map<String, AppCanonicalBoundaryService.PurchaseEligibilityDecision> decisions = result.getData();
            return candidates.stream().filter(candidate -> {
                AppCanonicalBoundaryService.PurchaseEligibilityDecision decision =
                        decisions.get(candidate.productNo());
                return decision != null && decision.eligible()
                        && candidate.productNo().equals(text(decision.productNo()));
            }).findFirst().orElse(null);
        } catch (RuntimeException exception) {
            // Home is a read projection. An eligibility outage must hide the
            // conversion card, not make the entire Home/Earn surface unavailable
            // and never fall back to a locally guessed product.
            return null;
        }
    }

    private PriceHistoryProjection priceHistory(List<TaskPriceHistoryRow> rows, LocalDateTime now) {
        if (rows == null || rows.isEmpty()) {
            return new PriceHistoryProjection(null, null);
        }
        LocalDateTime dayCutoff = now.minusHours(24);
        LocalDateTime hourCutoff = now.minusHours(1);
        List<TaskPriceHistoryRow> day = rows.stream()
                .filter(row -> !row.observedAt().isBefore(dayCutoff) && !row.observedAt().isAfter(now))
                .sorted(java.util.Comparator.comparing(TaskPriceHistoryRow::observedAt))
                .toList();
        BigDecimal deltaPct = null;
        if (day.size() >= 2) {
            BigDecimal first = day.get(0).price();
            BigDecimal last = day.get(day.size() - 1).price();
            if (first.signum() > 0) {
                deltaPct = last.subtract(first)
                        .multiply(new BigDecimal("100"))
                        .divide(first, 2, RoundingMode.HALF_UP);
            }
        }
        List<BigDecimal> sparkline = day.stream()
                .filter(row -> !row.observedAt().isBefore(hourCutoff))
                .map(TaskPriceHistoryRow::price)
                .toList();
        if (sparkline.size() < 2) {
            sparkline = null;
        } else if (sparkline.size() > 12) {
            sparkline = sparkline.subList(sparkline.size() - 12, sparkline.size());
        }
        return new PriceHistoryProjection(deltaPct, sparkline);
    }

    private record PriceHistoryProjection(BigDecimal deltaPct, List<BigDecimal> sparkline) { }

    private List<Map<String, Object>> earningsLedger(List<EarningsLedgerRow> rows) {
        if (rows == null) return List.of();
        return rows.stream().map(row -> linked("id", text(row.id()), "client", text(row.client()),
                "model", text(row.model()), "rewardUsdt", nonNegative(row.rewardUsdt()),
                "synthetic", false,
                "completedAt", row.completedAt() == null ? null
                        : row.completedAt().atZone(SERVER_ZONE).toInstant().toString())).toList();
    }

    private List<Map<String, Object>> sandboxLedger(List<MarketTaskRow> tasks, String runId, LocalDateTime now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> taskCodes = new LinkedHashSet<>();
        int index = 0;
        for (MarketTaskRow task : tasks) {
            String code = taskCode(task.taskClass());
            if (code == null || !taskCodes.add(code)) continue;
            BigDecimal min = nonNegative(task.minReward());
            BigDecimal max = nonNegative(task.maxReward());
            BigDecimal reward = min != null && max != null
                    ? min.add(max).divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP)
                    : nonNegative(task.price());
            if (reward == null) continue;
            rows.add(linked("id", "SB-" + code + "-" + Math.abs(runId.hashCode()) + "-" + index,
                    "client", text(task.name()), "model", firstModel(task.modelName()), "rewardUsdt", reward,
                    "synthetic", true,
                    "completedAt", now.minusSeconds(7L + index * 17L).atZone(SERVER_ZONE).toInstant().toString()));
            if (++index == 5) break;
        }
        return rows;
    }

    private List<Map<String, Object>> sandboxClients(List<MarketTaskRow> tasks, long onlineDevices) {
        List<MarketTaskRow> eligible = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        for (MarketTaskRow task : tasks) {
            String code = taskCode(task.taskClass());
            if (code != null && codes.add(code)) eligible.add(task);
            if (eligible.size() == 3) break;
        }
        if (eligible.isEmpty()) return List.of();
        long base = onlineDevices / eligible.size();
        long remainder = onlineDevices % eligible.size();
        List<Map<String, Object>> clients = new ArrayList<>();
        for (int i = 0; i < eligible.size(); i++) {
            MarketTaskRow row = eligible.get(i);
            clients.add(linked("id", taskCode(row.taskClass()), "name", text(row.name()),
                    "model", firstModel(row.modelName()), "city", "Sandbox",
                    "gpus", base + (i < remainder ? 1 : 0)));
        }
        return clients;
    }

    private Set<String> uniqueTaskCodes(List<MarketTaskRow> tasks) {
        Set<String> codes = new LinkedHashSet<>();
        for (MarketTaskRow task : tasks) {
            String code = taskCode(task.taskClass());
            if (code != null) codes.add(code);
        }
        return codes;
    }

    private Map<String, Object> promo() {
        PromoRow row = mapper.promo();
        if (row == null || row.status() == null) return null;
        String status = "active".equalsIgnoreCase(row.status()) ? "active" : "paused";
        LocalDateTime endAt = null;
        if (row.updatedAt() != null && row.countdownDays() != null && row.countdownHours() != null
                && row.countdownDays() >= 0 && row.countdownHours() >= 0
                && (row.countdownDays() > 0 || row.countdownHours() > 0)) {
            endAt = row.updatedAt().plusDays(row.countdownDays()).plusHours(row.countdownHours());
        }
        return linked("status", status, "rewardNex", nonNegative(decimal(row.baseReward())),
                "multiplier", nonNegative(decimal(row.multiplier())), "endAt", endAt == null ? null : endAt.atZone(SERVER_ZONE).toInstant().toString(),
                "product", linked("kind", productKind(row.targetDevice()), "name", text(row.targetDevice()),
                        "dailyUsdt", nonNegative(decimal(row.targetDaily())), "priceUsdt", nonNegative(row.productPriceUsdt())));
    }

    private List<Map<String, Object>> clients(List<OnGridClientRow> rows) {
        if (rows == null) return List.of();
        return rows.stream().map(row -> linked("id", text(row.id()), "name", text(row.name()), "model", text(row.model()),
                "city", text(row.city()), "gpus", nonNegativeLong(row.gpus()))).toList();
    }

    private Map<String, Object> period(PeriodRow row) {
        if (row == null || row.jobCount() == null || row.jobCount() == 0) return linked("usdt", null, "nex", null, "jobCount", null);
        return linked("usdt", nonNegative(row.usdt()), "nex", nonNegative(row.nex()), "jobCount", row.jobCount());
    }

    private BigDecimal percentageChange(PeriodRow current, PeriodRow previous) {
        if (current == null || current.jobCount() == null || current.jobCount() == 0 || current.usdt() == null
                || previous == null || previous.jobCount() == null || previous.jobCount() == 0
                || previous.usdt() == null || previous.usdt().signum() <= 0) {
            return null;
        }
        return current.usdt().subtract(previous.usdt())
                .multiply(new BigDecimal("100"))
                .divide(previous.usdt(), 2, RoundingMode.HALF_UP);
    }

    private Object fact(Object value, Long rows) { return rows == null || rows == 0 ? null : value; }
    private BigDecimal decimal(String value) { try { return value == null || value.isBlank() ? null : new BigDecimal(value.trim()); } catch (NumberFormatException ex) { return null; } }
    private BigDecimal decimal(Object value) { try { return value == null ? null : new BigDecimal(String.valueOf(value).trim()); } catch (NumberFormatException ex) { return null; } }
    private Long whole(Object value) { BigDecimal parsed = decimal(value); try { return parsed == null ? null : parsed.longValueExact(); } catch (ArithmeticException ex) { return null; } }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : null; }
    private BigDecimal nonNegative(BigDecimal value) { return value == null || value.signum() < 0 ? null : value; }
    private Long nonNegativeLong(Long value) { return value == null || value < 0 ? null : value; }
    private String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String firstModel(String value) { String normalized = text(value); return normalized == null ? null : normalized.split(",", 2)[0].trim(); }

    private String acceptanceRunId() {
        return runtimeEnvironment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
    }

    private boolean validSandboxRunId(String runId) {
        return RUN_ID.matcher(runId).matches() && !LEGACY_UNSCOPED_RUN_ID.equalsIgnoreCase(runId);
    }

    private <T> List<T> safe(List<T> rows) { return rows == null ? List.of() : rows; }

    private String taskCode(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
            case "IG", "IMAGE_GEN", "IMAGE_GENERATION" -> "IG";
            case "VG", "VIDEO_RENDER", "VIDEO_GENERATION" -> "VG";
            case "LL", "LLM_INFERENCE", "LLM" -> "LL";
            case "FT", "FINE_TUNE", "FINE_TUNING" -> "FT";
            case "EM", "EMBEDDING" -> "EM";
            case "SP", "SPEECH" -> "SP";
            default -> null;
        };
    }

    private String deviceKind(MarketProductRow row) { return productKind((row.productType() == null ? "" : row.productType()) + " " + (row.tier() == null ? "" : row.tier()) + " " + (row.productNo() == null ? "" : row.productNo())); }
    private String productKind(String value) {
        if (value == null) return null;
        String s = value.toUpperCase(Locale.ROOT).replace('_', '-');
        if (s.contains("PHONE")) return "phone";
        if (s.contains("CLOUD") || s.contains("SHARE")) return "cloud-share";
        if (s.contains("PRO-V2") || s.contains("PROV2")) return "stellarbox-pro-v2";
        if (s.contains("PRO")) return "stellarbox-pro";
        if (s.contains("RACK-P2") || s.contains("RACKP2")) return "stellarrack-p2";
        if (s.contains("RACK-P1") || s.contains("RACKP1")) return "stellarrack-p1";
        if (s.contains("S1") || s.contains("STELLARBOX")) return "stellarbox-s1";
        if (s.contains("GPU") || s.contains("PC")) return "pc-gpu";
        return null;
    }

    private Map<String, Object> linked(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); return result; }

    private static boolean isStrictProfile(String[] activeProfiles, Set<String> allowed, boolean allowEmpty) {
        if (activeProfiles == null || activeProfiles.length == 0) return allowEmpty;
        return activeProfiles.length == 1 && activeProfiles[0] != null
                && allowed.contains(activeProfiles[0].trim().toLowerCase(Locale.ROOT));
    }

}
