package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppTeamInsightsService {
    private static final java.time.ZoneId BUSINESS_ZONE =
            ffdd.opsconsole.shared.config.DateTimeFormatConfig.BUSINESS_ZONE;
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private static final Set<String> PERIODS = Set.of("today", "week", "month", "all");
    private static final Pattern SNAPSHOT_VERSION = Pattern.compile("[a-f0-9]{64}");
    private static final int LEADERBOARD_SNAPSHOT_MAX = 512;
    private static final long LEADERBOARD_SNAPSHOT_TTL_SECONDS = 300;
    private final AppTeamInsightsMapper mapper;
    private final LeadershipPoolConfigGuard leadershipPoolConfigGuard;
    private final PlatformConfigFacade configFacade;
    private final Environment environment;
    private final Map<String, LeaderboardSnapshot> leaderboardSnapshots = new ConcurrentHashMap<>();

    public ApiResult<Map<String, Object>> leaderboard(Long userId, String requestedPeriod) {
        return leaderboard(userId, requestedPeriod, 1, 100);
    }

    public ApiResult<Map<String, Object>> leaderboard(Long userId, String requestedPeriod, long requestedPage, long requestedPageSize) {
        return leaderboard(userId, requestedPeriod, requestedPage, requestedPageSize, null);
    }

    public ApiResult<Map<String, Object>> leaderboard(Long userId, String requestedPeriod, long requestedPage, long requestedPageSize,
                                                        String requestedSnapshotAt) {
        return leaderboard(userId, requestedPeriod, requestedPage, requestedPageSize, requestedSnapshotAt, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> leaderboard(Long userId, String requestedPeriod, long requestedPage, long requestedPageSize,
                                                        String requestedSnapshotAt, String requestedSnapshotVersion) {
        String period = requestedPeriod == null ? "week" : requestedPeriod.trim().toLowerCase();
        if (!PERIODS.contains(period)) return ApiResult.fail(422, "TEAM_LEADERBOARD_PERIOD_INVALID");
        if (requestedPage < 1 || requestedPageSize < 1 || requestedPageSize > 100) {
            return ApiResult.fail(422, "TEAM_LEADERBOARD_PAGE_INVALID");
        }
        Scope scope = scope(userId);
        Instant snapshotAt = snapshotAt(requestedSnapshotAt);
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.ok(TeamSandboxFactGenerator.leaderboard(scope.runId(), userId, period,
                    requestedPage, requestedPageSize));
        }
        LeaderboardSnapshot frozen = leaderboardSnapshot(scope, period, snapshotAt, requestedPage, requestedSnapshotVersion);
        List<AppTeamInsightsMapper.LeaderboardRow> source = frozen.candidates();
        List<Map<String, Object>> rows = new ArrayList<>();
        Integer myRank = null; BigDecimal gap = BigDecimal.ZERO;
        for (int index = 0; index < source.size(); index++) {
            var row = source.get(index);
            BigDecimal earned = zero(row.earnedUsdt());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", row.rank()); item.put("handle", mask(row.nickname(), row.userId()));
            item.put("flag", "🌐"); item.put("cc", "--"); item.put("directs", count(row.directs()));
            item.put("teamSize", count(row.teamSize())); item.put("earnedUSDT", earned); item.put("delta", 0);
            item.put("vRank", rank(row.vRank())); item.put("hasDevice", row.hasDevice() != null && row.hasDevice() == 1);
            rows.add(item);
            if (row.userId().equals(userId)) {
                myRank = row.rank();
                if (index > 0) gap = zero(source.get(index - 1).earnedUsdt()).subtract(earned).max(BigDecimal.ZERO);
            }
        }
        int from = pageStart(requestedPage, requestedPageSize, rows.size());
        int to = (int) Math.min(rows.size(), (long) from + requestedPageSize);
        Map<String, Object> result = provenance(scope);
        result.put("period", period); result.put("rows", rows.subList(from, to)); result.put("myRank", myRank);
        BigDecimal prizePool = frozen.paused() ? BigDecimal.ZERO : leaderboardPoolUsd(period);
        result.put("gapToNext", gap); result.put("poolUsd", prizePool);
        // A zero pool may still have a factual earned-volume ranking, but no
        // placement is represented as prize-eligible because F4 will not settle it.
        result.put("topN", !frozen.paused() && prizePool.signum() > 0 ? rows.size() : 0); result.put("page", requestedPage);
        result.put("pageSize", requestedPageSize); result.put("totalRows", rows.size());
        result.put("snapshotAt", snapshotAt.toString());
        result.put("snapshotVersion", frozen.version());
        result.put("generatedAt", Instant.now().toString());
        return ApiResult.ok(result);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> commissions(Long userId) {
        return commissions(userId, 1, 20);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> commissions(Long userId, long requestedPage, long requestedPageSize) {
        return commissions(userId, requestedPage, requestedPageSize, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> commissions(Long userId, long requestedPage, long requestedPageSize,
                                                       String requestedSnapshotAt) {
        if (!validPage(requestedPage, requestedPageSize)) {
            return ApiResult.fail(422, "TEAM_COMMISSIONS_PAGE_INVALID");
        }
        Scope scope = scope(userId);
        Instant snapshotAt = snapshotAt(requestedSnapshotAt);
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.ok(paginateSandboxEvents(
                    TeamSandboxFactGenerator.commissions(scope.runId(), userId), requestedPage, requestedPageSize));
        }
        LocalDateTime snapshotBoundary = LocalDateTime.ofInstant(snapshotAt, BUSINESS_ZONE);
        long totalRows = mapper.commissionEventCount(userId, snapshotBoundary);
        long offset = (requestedPage - 1) * requestedPageSize;
        List<Map<String, Object>> events = mapper.commissionEvents(userId, snapshotBoundary, offset, requestedPageSize)
                .stream().map(this::commission).toList();
        var summary = mapper.commissionSummary(userId, snapshotBoundary);
        Map<String, Object> aggregate = new LinkedHashMap<>(Map.of(
                "totalUSDT", zero(summary == null ? null : summary.totalUsdt()),
                "totalNEX", zero(summary == null ? null : summary.totalNex()),
                "directUSDT", zero(summary == null ? null : summary.directUsdt()),
                "extendedUSDT", zero(summary == null ? null : summary.extendedUsdt()),
                "contributorCount", count(summary == null ? null : summary.contributorCount())));
        LocalDate snapshotBusinessDate = snapshotBoundary.toLocalDate();
        SettlementWindow month = settlementWindow("month", snapshotBusinessDate);
        SettlementWindow today = settlementWindow("today", snapshotBusinessDate);
        var buckets = mapper.commissionBuckets(userId, month.fromInclusive(), month.toExclusive(),
                today.fromInclusive(), today.toExclusive(), snapshotBoundary);
        BigDecimal monthUsdt = BigDecimal.ZERO, monthNex = BigDecimal.ZERO, todayUsdt = BigDecimal.ZERO;
        BigDecimal unlockedUsdt = BigDecimal.ZERO, unlockedNex = BigDecimal.ZERO, coolingUsdt = BigDecimal.ZERO;
        LocalDateTime nextUnlock = null;
        Map<String, Map<String, Object>> byKind = new LinkedHashMap<>();
        int eventCount = 0;
        for (String kind : List.of("unilevel", "binary", "peer", "cultivation", "leadership", "genesis")) {
            byKind.put(kind, new LinkedHashMap<>(Map.of("usdt", BigDecimal.ZERO, "nex", BigDecimal.ZERO, "count", 0)));
        }
        for (var bucket : buckets) {
            monthUsdt = monthUsdt.add(zero(bucket.monthUsdt())); monthNex = monthNex.add(zero(bucket.monthNex()));
            todayUsdt = todayUsdt.add(zero(bucket.todayUsdt())); eventCount += count(bucket.eventCount());
            if ("unlocked".equals(status(bucket.status()))) {
                unlockedUsdt = unlockedUsdt.add(zero(bucket.totalUsdt())); unlockedNex = unlockedNex.add(zero(bucket.totalNex()));
            } else if ("cooling".equals(status(bucket.status()))) {
                coolingUsdt = coolingUsdt.add(zero(bucket.totalUsdt()));
                if (bucket.nextUnlockAt() != null && (nextUnlock == null || bucket.nextUnlockAt().isBefore(nextUnlock))) nextUnlock = bucket.nextUnlockAt();
            }
            Map<String, Object> group = byKind.get(kind(bucket.commissionType()));
            group.put("usdt", ((BigDecimal) group.get("usdt")).add(zero(bucket.totalUsdt())));
            group.put("nex", ((BigDecimal) group.get("nex")).add(zero(bucket.totalNex())));
            group.put("count", (Integer) group.get("count") + count(bucket.eventCount()));
        }
        aggregate.put("monthUSDT", monthUsdt); aggregate.put("monthNEX", monthNex); aggregate.put("todayUSDT", todayUsdt);
        aggregate.put("unlockedUSDT", unlockedUsdt); aggregate.put("unlockedNEX", unlockedNex); aggregate.put("coolingUSDT", coolingUsdt);
        aggregate.put("byKind", byKind); aggregate.put("eventCount", eventCount);
        aggregate.put("nextUnlockAt", nextUnlock == null ? null : nextUnlock.atZone(BUSINESS_ZONE).toInstant().toEpochMilli());
        Map<String, Object> result = provenance(scope); result.put("events", events); result.put("aggregate", aggregate);
        result.put("page", requestedPage); result.put("pageSize", requestedPageSize); result.put("totalRows", totalRows);
        result.put("snapshotAt", snapshotAt.toString());
        result.put("generatedAt", Instant.now().toString());
        return ApiResult.ok(result);
    }

    /**
     * Server-authoritative F2 projection.  The App receives the settlement
     * cycle, source, layer and USDT/NEX split as stored facts; it must not
     * reconstruct rewards from downline volume or client-side rate tables.
     */
    public ApiResult<Map<String, Object>> unilevel(Long userId, String requestedPeriod) {
        return unilevel(userId, requestedPeriod, 1, 20);
    }

    public ApiResult<Map<String, Object>> unilevel(
            Long userId, String requestedPeriod, long requestedPage, long requestedPageSize) {
        return unilevel(userId, requestedPeriod, requestedPage, requestedPageSize, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> unilevel(
            Long userId, String requestedPeriod, long requestedPage, long requestedPageSize, String requestedSnapshotAt) {
        String period = requestedPeriod == null ? "week" : requestedPeriod.trim().toLowerCase();
        if (!PERIODS.contains(period)) return ApiResult.fail(422, "TEAM_UNILEVEL_PERIOD_INVALID");
        if (!validPage(requestedPage, requestedPageSize)) return ApiResult.fail(422, "TEAM_UNILEVEL_PAGE_INVALID");
        Scope scope = scope(userId);
        Instant snapshotAt = snapshotAt(requestedSnapshotAt);
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.ok(paginateSandboxEvents(
                    TeamSandboxFactGenerator.unilevel(scope.runId(), userId, period), requestedPage, requestedPageSize));
        }
        LocalDateTime snapshotBoundary = LocalDateTime.ofInstant(snapshotAt, BUSINESS_ZONE);
        SettlementWindow window = settlementWindow(period, snapshotBoundary.toLocalDate());
        long totalRows = mapper.unilevelEventCount(userId, scope.sandbox(), snapshotBoundary,
                window.fromInclusive(), window.toExclusive());
        long offset = (requestedPage - 1) * requestedPageSize;
        List<Map<String, Object>> events = mapper.unilevelEvents(
                        userId, scope.sandbox(), snapshotBoundary, window.fromInclusive(), window.toExclusive(),
                        offset, requestedPageSize).stream()
                .map(this::unilevel).toList();
        var summary = mapper.unilevelSplit(userId, scope.sandbox(), snapshotBoundary,
                window.fromInclusive(), window.toExclusive());
        Map<String, Object> direct = Map.of(
                "amountUSDT", zero(summary == null ? null : summary.directUsdt()),
                "amountNEX", zero(summary == null ? null : summary.directNex()),
                "count", count(summary == null ? null : summary.directCount()));
        Map<String, Object> extended = Map.of(
                "amountUSDT", zero(summary == null ? null : summary.extendedUsdt()),
                "amountNEX", zero(summary == null ? null : summary.extendedNex()),
                "count", count(summary == null ? null : summary.extendedCount()));
        Map<String, Object> result = provenance(scope);
        result.put("period", period);
        result.put("events", events);
        result.put("split", Map.of("direct", direct, "extended", extended));
        result.put("page", requestedPage); result.put("pageSize", requestedPageSize); result.put("totalRows", totalRows);
        result.put("snapshotAt", snapshotAt.toString());
        result.put("generatedAt", Instant.now().toString());
        return ApiResult.ok(result);
    }

    private boolean validPage(long page, long pageSize) {
        return page >= 1 && page <= 1_000_000 && pageSize >= 1 && pageSize <= 100;
    }

    private Instant snapshotAt(String raw) {
        if (raw == null || raw.isBlank()) return Instant.now();
        try {
            Instant value = Instant.parse(raw.trim());
            if (value.isAfter(Instant.now().plusSeconds(60))) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException ex) {
            throw new BizException(422, "TEAM_SNAPSHOT_INVALID");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paginateSandboxEvents(
            Map<String, Object> source, long page, long pageSize) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        List<Object> events = source.get("events") instanceof List<?> list
                ? new ArrayList<>((List<Object>) list) : List.of();
        int from = pageStart(page, pageSize, events.size());
        int to = (int) Math.min(events.size(), (long) from + pageSize);
        result.put("events", events.subList(from, to));
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("totalRows", events.size());
        return result;
    }

    public ApiResult<Map<String, Object>> leadershipPool(Long userId) {
        Scope scope = scope(userId);
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.ok(TeamSandboxFactGenerator.leadershipPool(scope.runId(), userId));
        }
        LeadershipPoolConfigGuard.SettlementConfig config;
        try {
            config = leadershipPoolConfigGuard.requireValid();
        } catch (LeadershipPoolConfigGuard.ConfigUnavailableException unavailable) {
            Map<String, Object> hold = provenance(scope);
            hold.put("available", false);
            hold.put("state", "HOLD");
            hold.put("reason", "F4_SETTLEMENT_CONFIG_UNAVAILABLE");
            hold.put("problem", Map.of(
                    "key", unavailable.key(),
                    "reason", unavailable.reason(),
                    "valueFingerprint", unavailable.valueFingerprint()));
            return ApiResult.fail(503, "F4_LEADERSHIP_POOL_HOLD", hold);
        }
        var distribution = mapper.rankDistribution(scope.sandbox(), config.unlockRank());
        long totalVotes = distribution.stream().mapToLong(row -> (long) count(row.people()) * count(row.votes())).sum();
        int myRank = rank(scope.vRank());
        int myVotes = distribution.stream().filter(row -> row.vRank() != null && row.vRank() == myRank)
                .map(AppTeamInsightsMapper.RankDistributionRow::votes).findFirst().orElse(0);
        SettlementWindow window = settlementWindow("week");
        BigDecimal pool = LeadershipPoolProjection.amount(mapper.leadershipPoolSummary(), config);
        BigDecimal share = totalVotes == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(myVotes)
                .divide(BigDecimal.valueOf(totalVotes), 12, RoundingMode.DOWN);
        List<Map<String, Object>> dist = distribution.stream().map(row -> Map.<String, Object>of(
                "vRank", count(row.vRank()), "people", count(row.people()), "votes", count(row.votes()))).toList();
        List<Map<String, Object>> history = mapper.leadershipHistory(userId, window.fromInclusive()).stream().map(row -> Map.<String, Object>of(
                "weekId", row.weekId(), "payoutUSDT", zero(row.payoutUsdt()))).toList();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime next = CronExpression.parse(config.springCron()).next(now);
        Map<String, Object> result = provenance(scope);
        result.put("currentWeekPoolUSDT", pool); result.put("myRank", myRank); result.put("myVotes", myVotes);
        result.put("totalVotes", totalVotes); result.put("mySharePct", share); result.put("projectedPayoutUSDT", pool.multiply(share));
        result.put("distribution", dist); result.put("history", history); result.put("nextPayoutAt", next == null ? now.toInstant().toString() : next.toInstant().toString());
        result.put("unlockRank", config.unlockRank()); result.put("injectRate", config.injectRate());
        // Same display configuration and absent-value default as PC F1. This
        // controls concentration presentation only, never settlement amounts.
        result.put("topN", configuredMoney("team.ui.F.vrank.leadership.topN").intValue());
        return ApiResult.ok(result);
    }

    private Map<String, Object> commission(AppTeamInsightsMapper.CommissionRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "CM-" + row.id()); item.put("kind", kind(row.commissionType()));
        item.put("sourceUserName", row.sourceUserName() == null || row.sourceUserName().isBlank() ? "System" : row.sourceUserName());
        item.put("layer", row.layerNo()); item.put("orderId", row.orderNo()); item.put("orderAmountUSD", row.orderAmountUsd());
        item.put("amountUSDT", zero(row.amountUsdt())); item.put("amountNEX", zero(row.amountNex()));
        item.put("ts", row.createdAt().atZone(BUSINESS_ZONE).toInstant().toEpochMilli());
        item.put("unlockAt", row.unlockAt() == null ? row.createdAt().atZone(BUSINESS_ZONE).toInstant().toEpochMilli()
                : row.unlockAt().atZone(BUSINESS_ZONE).toInstant().toEpochMilli());
        String status = status(row.status());
        item.put("status", status); item.put("settlementState", "CANONICAL");
        item.put("withdrawable", "unlocked".equals(status)); return item;
    }

    private Map<String, Object> unilevel(AppTeamInsightsMapper.UnilevelRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "CM-" + row.id());
        item.put("source", "network");
        item.put("sourceUserName", row.sourceUserName() == null || row.sourceUserName().isBlank() ? "System" : row.sourceUserName());
        item.put("cycle", row.cycle());
        item.put("layer", row.layerNo());
        item.put("orderId", row.orderNo());
        item.put("orderAmountUSD", zero(row.orderAmountUsd()));
        item.put("amountUSDT", zero(row.amountUsdt()));
        item.put("amountNEX", zero(row.amountNex()));
        item.put("currency", row.currency());
        item.put("ts", row.createdAt().atZone(BUSINESS_ZONE).toInstant().toEpochMilli());
        item.put("unlockAt", row.unlockAt() == null ? row.createdAt().atZone(BUSINESS_ZONE).toInstant().toEpochMilli()
                : row.unlockAt().atZone(BUSINESS_ZONE).toInstant().toEpochMilli());
        String status = status(row.status());
        item.put("status", status); item.put("settlementState", "CANONICAL");
        item.put("withdrawable", "unlocked".equals(status));
        return item;
    }

    private Map<String, Object> split(List<Map<String, Object>> events, boolean direct) {
        BigDecimal usdt = BigDecimal.ZERO;
        BigDecimal nex = BigDecimal.ZERO;
        int count = 0;
        for (Map<String, Object> event : events) {
            Object rawLayer = event.get("layer");
            if (!(rawLayer instanceof Number layer) || (layer.intValue() == 1) != direct) continue;
            usdt = usdt.add(zero((BigDecimal) event.get("amountUSDT")));
            nex = nex.add(zero((BigDecimal) event.get("amountNEX")));
            count++;
        }
        return Map.of("amountUSDT", usdt, "amountNEX", nex, "count", count);
    }

    private Scope scope(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        var user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "TEAM_USER_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles()).map(String::trim).map(String::toLowerCase)
                .filter(value -> !value.isBlank()).collect(Collectors.toSet());
        boolean development = false;
        boolean isolated = profiles.size() == 1 && "test".equals(profiles.iterator().next());
        boolean production = profiles.isEmpty() || (profiles.size() == 1 && Set.of("dev", "prod").contains(profiles.iterator().next()));
        if (!development && !isolated && !production) throw new BizException(503, "TEAM_PROFILE_INVALID");
        if (isolated && user.sandbox() != 1) throw new BizException(403, "TEAM_SANDBOX_USER_REQUIRED");
        if (production && user.sandbox() != 0) throw new BizException(403, "TEAM_PRODUCTION_USER_REQUIRED");
        if (production) return new Scope(0, user.vRank(), "PRODUCTION", "");
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
        if (!RUN_ID.matcher(runId).matches()) throw new BizException(503, "TEAM_RUN_ID_REQUIRED");
        return new Scope(1, user.vRank(), "SANDBOX", runId);
    }

    private Map<String, Object> provenance(Scope scope) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("source", "server"); result.put("serverCanonical", true);
        result.put("sourceEnvironment", scope.sourceEnvironment()); result.put("runId", scope.runId()); return result;
    }

    /**
     * Cursor pages are served from the immutable candidate list created for page one.
     * A missing/expired version is deliberately rejected so the client refreshes instead
     * of quietly mixing a later ranking with an earlier page.
     */
    private LeaderboardSnapshot leaderboardSnapshot(Scope scope, String period, Instant snapshotAt,
                                                    long page, String requestedVersion) {
        if (requestedVersion != null && !requestedVersion.isBlank()
                && !SNAPSHOT_VERSION.matcher(requestedVersion).matches()) {
            throw new BizException(422, "TEAM_LEADERBOARD_SNAPSHOT_VERSION_INVALID");
        }
        if (page > 1) {
            if (requestedVersion == null || requestedVersion.isBlank()) {
                throw new BizException(422, "TEAM_LEADERBOARD_SNAPSHOT_VERSION_REQUIRED");
            }
            LeaderboardSnapshot frozen = leaderboardSnapshots.get(requestedVersion);
            if (frozen == null || frozen.expiresAt().isBefore(Instant.now())
                    || !frozen.matches(scope, period, snapshotAt)) {
                if (frozen != null) leaderboardSnapshots.remove(requestedVersion, frozen);
                throw new BizException(409, "TEAM_LEADERBOARD_SNAPSHOT_STALE");
            }
            return frozen;
        }

        boolean paused = leaderboardPaused();
        List<AppTeamInsightsMapper.LeaderboardRow> candidates;
        if (paused) {
            candidates = List.of();
        } else {
            SettlementWindow window = settlementWindow(period,
                    LocalDateTime.ofInstant(snapshotAt, BUSINESS_ZONE).toLocalDate());
            candidates = List.copyOf(mapper.leaderboardEligible(
                    leaderboardActionPeriod(period), scope.sandbox(), window.fromInclusive(), window.toExclusive(),
                    configuredMoney("team.ui.F.leaderboard.minUsd"), leaderboardTopN(period),
                    LocalDateTime.ofInstant(snapshotAt, BUSINESS_ZONE)));
        }
        String version = leaderboardSnapshotVersion(scope, period, snapshotAt, paused, candidates);
        LeaderboardSnapshot frozen = new LeaderboardSnapshot(version, scope.sandbox(), period, snapshotAt, paused,
                candidates, Instant.now().plusSeconds(LEADERBOARD_SNAPSHOT_TTL_SECONDS));
        leaderboardSnapshots.put(version, frozen);
        if (leaderboardSnapshots.size() > LEADERBOARD_SNAPSHOT_MAX) {
            Instant now = Instant.now();
            leaderboardSnapshots.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
            for (var entry : leaderboardSnapshots.entrySet()) {
                if (leaderboardSnapshots.size() <= LEADERBOARD_SNAPSHOT_MAX) break;
                leaderboardSnapshots.remove(entry.getKey(), entry.getValue());
            }
        }
        return frozen;
    }

    private String leaderboardSnapshotVersion(Scope scope, String period, Instant snapshotAt, boolean paused,
                                              List<AppTeamInsightsMapper.LeaderboardRow> candidates) {
        StringBuilder material = new StringBuilder("leaderboard-v1|")
                .append(scope.sandbox()).append('|').append(period).append('|').append(snapshotAt).append('|').append(paused);
        for (var candidate : candidates) {
            material.append('|').append(candidate.rank()).append('|').append(candidate.userId()).append('|')
                    .append(candidate.nickname()).append('|').append(candidate.vRank()).append('|')
                    .append(candidate.earnedUsdt()).append('|').append(candidate.directs()).append('|')
                    .append(candidate.teamSize()).append('|').append(candidate.hasDevice());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private String kind(String value) { String normalized=value==null?"":value.toLowerCase(); return switch(normalized){
        case "direct","network","unilevel" -> "unilevel"; case "binary" -> "binary"; case "peer" -> "peer";
        case "cultivation" -> "cultivation"; case "leadership","leaderboard_prize" -> "leadership"; default -> "genesis"; }; }
    private String status(String value) { String normalized=value==null?"":value.toUpperCase(); return switch(normalized){
        case "UNLOCKED","AVAILABLE" -> "unlocked";
        case "SETTLED","PAID","WITHDRAWN" -> "withdrawn";
        case "PENDING","COOLING","LOCKED" -> "cooling";
        case "FROZEN" -> "frozen";
        case "REVERSED","ROLLBACK" -> "reversed";
        case "REJECTED" -> "rejected";
        default -> throw new BizException(503, "COMMISSION_STATUS_INVALID"); }; }
    private String mask(String value, Long userId) { String name=value==null||value.isBlank()?"User"+userId:value.trim(); return name.length()<3?name:name.substring(0,Math.min(3,name.length()))+"***"; }
    private BigDecimal zero(BigDecimal value) { return value==null||value.signum()<0?BigDecimal.ZERO:value; }
    private int count(Integer value) { return value==null||value<0?0:value; }
    private BigDecimal leaderboardPoolUsd(String period) {
        if ("week".equals(period)) {
            BigDecimal override = configuredMoney("team.ui.F.leaderboard.poolUsd");
            if (override.signum() > 0) return override;
        }
        String field = "all".equals(period) ? "allTime" : period;
        return configFacade.activeValue("team.ui.F.pool.periodPrize")
                .map(value -> configuredPeriodPrize(value, field)).orElse(BigDecimal.ZERO);
    }
    private BigDecimal configuredPeriodPrize(String value, String field) {
        java.util.regex.Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                .matcher(value == null ? "" : value);
        if (!matcher.find()) return BigDecimal.ZERO;
        try { return new BigDecimal(matcher.group(1)); } catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }
    private BigDecimal configuredMoney(String key) {
        return configFacade.activeValue(key).map(value -> {
            try {
                BigDecimal amount = new BigDecimal(value.trim().replace("$", "").replace(",", ""));
                return amount.signum() < 0 ? BigDecimal.ZERO : amount;
            } catch (RuntimeException ignored) {
                return BigDecimal.ZERO;
            }
        }).orElse(BigDecimal.ZERO);
    }
    private boolean leaderboardPaused() {
        return configFacade.activeValue("team.ui.F.leaderboard.paused")
                .map(value -> "on".equalsIgnoreCase(value.trim()) || "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()))
                .orElse(false);
    }
    /**
     * Same UTC calendar construction used by F4's settlement service. The
     * datasource pins MySQL sessions to +08:00, so the mapper receives these
     * explicit LocalDateTime bounds instead of deriving a separate CURDATE/NOW
     * boundary from that session clock.
     */
    private SettlementWindow settlementWindow(String period) {
        return settlementWindow(period, LocalDate.now(BUSINESS_ZONE));
    }
    private SettlementWindow settlementWindow(String period, LocalDate today) {
        return switch (period) {
            case "today" -> new SettlementWindow(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
            case "week" -> {
                LocalDate monday = today.with(DayOfWeek.MONDAY);
                yield new SettlementWindow(monday.atStartOfDay(), monday.plusWeeks(1).atStartOfDay());
            }
            case "month" -> new SettlementWindow(today.withDayOfMonth(1).atStartOfDay(),
                    today.withDayOfMonth(1).plusMonths(1).atStartOfDay());
            default -> new SettlementWindow(null, null);
        };
    }
    private int leaderboardTopN(String period) {
        return switch (period) { case "today" -> 20; case "week" -> 50; default -> 100; };
    }
    private String leaderboardActionPeriod(String period) { return "all".equals(period) ? "allTime" : period; }
    private int rank(String value) { try{return Math.max(0,Math.min(12,Integer.parseInt(value==null?"0":value.replaceFirst("^[Vv]",""))));}catch(RuntimeException ignored){return 0;} }
    private int pageStart(long page, long pageSize, int total) {
        if (page <= 1) return 0;
        if (page > Long.MAX_VALUE / pageSize) return total;
        long offset = (page - 1L) * pageSize;
        return offset >= total ? total : (int) offset;
    }
    private record LeaderboardSnapshot(String version, Integer sandbox, String period, Instant snapshotAt, boolean paused,
                                       List<AppTeamInsightsMapper.LeaderboardRow> candidates, Instant expiresAt) {
        private boolean matches(Scope scope, String requestedPeriod, Instant requestedSnapshotAt) {
            return sandbox.equals(scope.sandbox()) && period.equals(requestedPeriod) && snapshotAt.equals(requestedSnapshotAt);
        }
    }
    private record Scope(Integer sandbox, String vRank, String sourceEnvironment, String runId) { }
    private record SettlementWindow(LocalDateTime fromInclusive, LocalDateTime toExclusive) { }
}
