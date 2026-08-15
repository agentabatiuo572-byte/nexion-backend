package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppNetworkRankMapper;
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

/** Current network rank projection; 24h delta remains unavailable without a server snapshot source. */
@Service
@RequiredArgsConstructor
public class AppNetworkRankService {
    private static final Set<String> ISOLATED = Set.of("acceptance", "test", "local-sandbox");
    private static final Set<String> PRODUCTION = Set.of("production", "default");
    private final AppNetworkRankMapper mapper;
    private final Environment environment;

    public ApiResult<Map<String, Object>> snapshot(Long userId) {
        Scope scope = scope(userId);
        List<AppNetworkRankMapper.RankRow> rows = mapper.rankedUsers(scope.sandbox());
        if (rows == null) rows = List.of();
        Integer rank = null;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).userId().equals(userId)) { rank = i + 1; break; }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "nx_user_device");
        result.put("sourceEnvironment", scope.environment());
        result.put("runId", scope.runId());
        result.put("currentRank", rank);
        // There is no durable 24h rank sample in the current schema. Do not guess or persist it client-side.
        result.put("rankChange24h", null);
        result.put("snapshotAvailable", false);
        result.put("generatedAt", Instant.now().toString());
        return ApiResult.ok(result);
    }

    private Scope scope(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        AppNetworkRankMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "NETWORK_RANK_USER_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles()).map(String::trim).map(String::toLowerCase)
                .filter(value -> !value.isBlank()).collect(Collectors.toSet());
        boolean isolated = profiles.size() == 1 && ISOLATED.contains(profiles.iterator().next());
        boolean production = profiles.isEmpty() || profiles.size() == 1 && PRODUCTION.contains(profiles.iterator().next());
        if (!isolated && !production) throw new BizException(503, "NETWORK_RANK_PROFILE_INVALID");
        if (isolated && user.sandbox() != 1) throw new BizException(403, "NETWORK_RANK_SANDBOX_USER_REQUIRED");
        if (production && user.sandbox() != 0) throw new BizException(403, "NETWORK_RANK_PRODUCTION_USER_REQUIRED");
        if (isolated) {
            String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
            if (!runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) throw new BizException(503, "NETWORK_RANK_RUN_ID_REQUIRED");
            return new Scope(1, "SANDBOX", runId);
        }
        return new Scope(0, "PRODUCTION", "");
    }

    private record Scope(Integer sandbox, String environment, String runId) { }
}
