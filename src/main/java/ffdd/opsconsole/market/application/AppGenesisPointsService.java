package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.AppGenesisPointsMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Server-authoritative, environment-isolated Genesis points leaderboard. */
@Service
@RequiredArgsConstructor
public class AppGenesisPointsService {
    private static final int POINTS_PER_HOLDING = 1_000;
    private static final Set<String> ISOLATED_PROFILES = Set.of("dev", "test");
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod");
    private final AppGenesisPointsMapper mapper;
    private final Environment environment;

    public ApiResult<Map<String, Object>> projection(Long userId) {
        Scope scope = scope(userId);
        List<AppGenesisPointsMapper.PointsRow> source = scope.sandbox()==1
                ? mapper.sandboxLeaderboard(scope.runId()) : mapper.leaderboard(0);
        if (source == null) source = List.of();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        AppGenesisPointsMapper.PointsRow currentSource = scope.sandbox()==1
                ? mapper.sandboxCurrentUser(userId,scope.runId()) : mapper.currentUser(userId,0);
        int currentHoldings = currentSource == null ? 0 : Math.max(0, safeLong(currentSource.holdings()));
        int currentPoints = Math.multiplyExact(currentHoldings, POINTS_PER_HOLDING);
        Integer rankSource = currentSource == null ? null : scope.sandbox()==1
                ? mapper.sandboxCurrentRank(userId,scope.runId()) : mapper.currentRank(userId,0);
        Integer currentRank = rankSource == null || rankSource < 1 ? null : rankSource;
        for (int i = 0; i < source.size(); i++) {
            AppGenesisPointsMapper.PointsRow row = source.get(i);
            int holdings = Math.max(0, safeLong(row.holdings()));
            int points = Math.multiplyExact(holdings, POINTS_PER_HOLDING);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("handle", mask(row.nickname(), row.userId()));
            item.put("points", points);
            item.put("holdings", holdings);
            rows.add(item);
            if (row.userId().equals(userId) && currentRank == null) {
                currentRank = i + 1;
                currentPoints = points;
                currentHoldings = holdings;
            }
        }
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("rank", currentRank);
        current.put("points", currentPoints);
        current.put("holdings", currentHoldings);
        return ApiResult.ok(new LinkedHashMap<>(Map.of(
                "serverCanonical", true,
                "source", scope.sandbox()==1 ? "mock" : "nx_genesis_holding",
                "sourceEnvironment", scope.sourceEnvironment(),
                "runId", scope.runId(),
                "pointsPerHolding", POINTS_PER_HOLDING,
                "leaderboard", rows,
                "currentUser", current,
                "generatedAt", Instant.now().toString())));
    }

    private Scope scope(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        AppGenesisPointsMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "GENESIS_USER_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles()).map(String::trim)
                .map(String::toLowerCase).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        boolean isolated = profiles.size() == 1 && ISOLATED_PROFILES.contains(profiles.iterator().next());
        boolean production = profiles.isEmpty() || (profiles.size() == 1 && PRODUCTION_PROFILES.contains(profiles.iterator().next()));
        if (!isolated && !production) throw new BizException(503, "GENESIS_PROFILE_INVALID");
        if (isolated && user.sandbox() != 1) throw new BizException(403, "GENESIS_SANDBOX_USER_REQUIRED");
        if (production && user.sandbox() != 0) throw new BizException(403, "GENESIS_PRODUCTION_USER_REQUIRED");
        if (isolated) {
            String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
            if (!runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}")) throw new BizException(503, "GENESIS_RUN_ID_REQUIRED");
            return new Scope(1, "SANDBOX", runId);
        }
        return new Scope(0, "PRODUCTION", "");
    }

    private int safeLong(Long value) {
        if (value == null || value <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE / POINTS_PER_HOLDING, value);
    }

    private String mask(String nickname, Long userId) {
        String value = nickname == null || nickname.isBlank() ? "User " + userId : nickname.trim();
        return value.length() < 3 ? value : value.substring(0, Math.min(3, value.length())) + "***";
    }

    private record Scope(Integer sandbox, String sourceEnvironment, String runId) { }
}
