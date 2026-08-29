package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppTeamInsightsService {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private static final Set<String> PERIODS = Set.of("today", "week", "month", "all");
    private final AppTeamInsightsMapper mapper;
    private final Environment environment;

    public ApiResult<Map<String, Object>> leaderboard(Long userId, String requestedPeriod) {
        return leaderboard(userId, requestedPeriod, 1, 100);
    }

    public ApiResult<Map<String, Object>> leaderboard(Long userId, String requestedPeriod, long requestedPage, long requestedPageSize) {
        String period = requestedPeriod == null ? "week" : requestedPeriod.trim().toLowerCase();
        if (!PERIODS.contains(period)) return ApiResult.fail(422, "TEAM_LEADERBOARD_PERIOD_INVALID");
        if (requestedPage < 1 || requestedPageSize < 1 || requestedPageSize > 100) {
            return ApiResult.fail(422, "TEAM_LEADERBOARD_PAGE_INVALID");
        }
        Scope scope = scope(userId);
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.ok(TeamSandboxFactGenerator.leaderboard(scope.runId(), userId, period,
                    requestedPage, requestedPageSize));
        }
        List<AppTeamInsightsMapper.LeaderboardRow> source = mapper.leaderboard(period, scope.sandbox());
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
        result.put("gapToNext", gap); result.put("poolUsd", BigDecimal.ZERO);
        result.put("topN", Math.min(100, rows.size())); result.put("page", requestedPage);
        result.put("pageSize", requestedPageSize); result.put("totalRows", rows.size());
        result.put("generatedAt", Instant.now().toString());
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String, Object>> commissions(Long userId) {
        Scope scope = scope(userId);
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.ok(TeamSandboxFactGenerator.commissions(scope.runId(), userId));
        }
        List<Map<String, Object>> events = mapper.commissionEvents(userId, 100).stream().map(this::commission).toList();
        Map<String, Object> result = provenance(scope); result.put("events", events); result.put("generatedAt", Instant.now().toString());
        return ApiResult.ok(result);
    }

    /**
     * Server-authoritative F2 projection.  The App receives the settlement
     * cycle, source, layer and USDT/NEX split as stored facts; it must not
     * reconstruct rewards from downline volume or client-side rate tables.
     */
    public ApiResult<Map<String, Object>> unilevel(Long userId, String requestedPeriod) {
        String period = requestedPeriod == null ? "week" : requestedPeriod.trim().toLowerCase();
        if (!PERIODS.contains(period)) return ApiResult.fail(422, "TEAM_UNILEVEL_PERIOD_INVALID");
        Scope scope = scope(userId);
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.ok(TeamSandboxFactGenerator.unilevel(scope.runId(), userId, period));
        }
        List<Map<String, Object>> events = mapper.unilevelEvents(userId, scope.sandbox(), period).stream()
                .map(this::unilevel).toList();
        Map<String, Object> direct = split(events, true);
        Map<String, Object> extended = split(events, false);
        Map<String, Object> result = provenance(scope);
        result.put("period", period);
        result.put("events", events);
        result.put("split", Map.of("direct", direct, "extended", extended));
        result.put("generatedAt", Instant.now().toString());
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String, Object>> leadershipPool(Long userId) {
        Scope scope = scope(userId);
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.ok(TeamSandboxFactGenerator.leadershipPool(scope.runId(), userId));
        }
        var distribution = mapper.rankDistribution(scope.sandbox());
        long totalVotes = distribution.stream().mapToLong(row -> (long) count(row.people()) * count(row.votes())).sum();
        int myRank = rank(scope.vRank());
        int myVotes = distribution.stream().filter(row -> row.vRank() != null && row.vRank() == myRank)
                .map(AppTeamInsightsMapper.RankDistributionRow::votes).findFirst().orElse(0);
        BigDecimal pool = zero(mapper.currentLeadershipPool(scope.sandbox()));
        BigDecimal share = totalVotes == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(myVotes)
                .divide(BigDecimal.valueOf(totalVotes), 12, RoundingMode.DOWN);
        List<Map<String, Object>> dist = distribution.stream().map(row -> Map.<String, Object>of(
                "vRank", count(row.vRank()), "people", count(row.people()), "votes", count(row.votes()))).toList();
        List<Map<String, Object>> history = mapper.leadershipHistory(userId).stream().map(row -> Map.<String, Object>of(
                "weekId", row.weekId(), "payoutUSDT", zero(row.payoutUsdt()))).toList();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime next = now.with(DayOfWeek.MONDAY).toLocalDate().plusWeeks(1).atStartOfDay(ZoneOffset.UTC);
        Map<String, Object> result = provenance(scope);
        result.put("currentWeekPoolUSDT", pool); result.put("myRank", myRank); result.put("myVotes", myVotes);
        result.put("totalVotes", totalVotes); result.put("mySharePct", share); result.put("projectedPayoutUSDT", pool.multiply(share));
        result.put("distribution", dist); result.put("history", history); result.put("nextPayoutAt", next.toInstant().toString());
        return ApiResult.ok(result);
    }

    private Map<String, Object> commission(AppTeamInsightsMapper.CommissionRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "CM-" + row.id()); item.put("kind", kind(row.commissionType()));
        item.put("sourceUserName", row.sourceUserName() == null || row.sourceUserName().isBlank() ? "System" : row.sourceUserName());
        item.put("layer", row.layerNo()); item.put("orderId", row.orderNo()); item.put("orderAmountUSD", row.orderAmountUsd());
        item.put("amountUSDT", zero(row.amountUsdt())); item.put("amountNEX", zero(row.amountNex()));
        item.put("ts", row.createdAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        item.put("unlockAt", row.unlockAt() == null ? row.createdAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                : row.unlockAt().toInstant(ZoneOffset.UTC).toEpochMilli());
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
        item.put("ts", row.createdAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        item.put("unlockAt", row.unlockAt() == null ? row.createdAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                : row.unlockAt().toInstant(ZoneOffset.UTC).toEpochMilli());
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
    private String kind(String value) { String normalized=value==null?"":value.toLowerCase(); return switch(normalized){
        case "direct","network","unilevel" -> "unilevel"; case "binary" -> "binary"; case "peer" -> "peer";
        case "cultivation" -> "cultivation"; case "leadership","leaderboard_prize" -> "leadership"; default -> "genesis"; }; }
    private String status(String value) { String normalized=value==null?"":value.toUpperCase(); return switch(normalized){
        case "UNLOCKED","AVAILABLE","SETTLED","PAID" -> "unlocked"; case "WITHDRAWN" -> "withdrawn"; default -> "cooling"; }; }
    private String mask(String value, Long userId) { String name=value==null||value.isBlank()?"User"+userId:value.trim(); return name.length()<3?name:name.substring(0,Math.min(3,name.length()))+"***"; }
    private BigDecimal zero(BigDecimal value) { return value==null||value.signum()<0?BigDecimal.ZERO:value; }
    private int count(Integer value) { return value==null||value<0?0:value; }
    private int rank(String value) { try{return Math.max(0,Math.min(12,Integer.parseInt(value==null?"0":value.replaceFirst("^[Vv]",""))));}catch(RuntimeException ignored){return 0;} }
    private int pageStart(long page, long pageSize, int total) {
        if (page <= 1) return 0;
        if (page > Long.MAX_VALUE / pageSize) return total;
        long offset = (page - 1L) * pageSize;
        return offset >= total ? total : (int) offset;
    }
    private void requireDevelopmentUser(Long userId, Integer sandbox) {
        if (!Integer.valueOf(1).equals(sandbox)) throw new BizException(403, "TEAM_DEVELOPMENT_USER_REQUIRED");
    }
    private record Scope(Integer sandbox, String vRank, String sourceEnvironment, String runId) { }
}
