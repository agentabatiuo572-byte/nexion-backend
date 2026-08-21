package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppNetworkRankMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Current network rank projection; 24h delta remains unavailable without a server snapshot source. */
@Service
@RequiredArgsConstructor
public class AppNetworkRankService {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private final AppNetworkRankMapper mapper;
    private final Environment environment;

    public ApiResult<Map<String, Object>> snapshot(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        Scope scope = scope(userId);
        List<AppNetworkRankMapper.RankRow> rows = scope.sandbox()
                ? mapper.rankedSandboxUsers(scope.runId())
                : scope.development() ? mapper.rankedDevelopmentUsers() : mapper.rankedUsers();
        if (rows == null) rows = List.of();
        Integer rank = null;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).userId().equals(userId)) { rank = i + 1; break; }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "nx_user_device");
        result.put("sourceEnvironment", scope.environment());
        result.put("runId", scope.runId());
        result.put("serverCanonical", true);
        result.put("currentRank", rank);
        // There is no durable 24h rank sample in the current schema. Do not guess or persist it client-side.
        result.put("rankChange24h", null);
        result.put("snapshotAvailable", false);
        result.put("generatedAt", Instant.now().toString());
        return ApiResult.ok(result);
    }

    private Scope scope(Long userId) {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        boolean development = ffdd.opsconsole.finance.application.FundsSandboxProfileGuard
                .isStrictDevelopmentProfile(profiles);
        if (development) {
            AppNetworkRankMapper.UserScope user = mapper.userScope(userId);
            if (user == null || user.sandbox() == null || !Integer.valueOf(1).equals(user.sandbox())) {
                throw new BizException(403, "NETWORK_RANK_SANDBOX_USER_REQUIRED");
            }
            return new Scope(false, true, "PRODUCTION", "");
        }
        UserAuthEnvironment runtime = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "NETWORK_RANK_RUNTIME_UNSUPPORTED"));
        String runId = "";
        if (runtime == UserAuthEnvironment.SANDBOX) {
            runId = environment == null ? "" : environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
            if (!RUN_ID.matcher(runId).matches() || "LEGACY_UNSCOPED".equalsIgnoreCase(runId)) {
                throw new BizException(503, "NETWORK_RANK_SANDBOX_RUN_ID_REQUIRED");
            }
        }
        AppNetworkRankMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "NETWORK_RANK_USER_REQUIRED");
        if (!runtime.acceptsSandbox(user.sandbox())) {
            throw new BizException(403, runtime == UserAuthEnvironment.SANDBOX
                    ? "NETWORK_RANK_SANDBOX_USER_REQUIRED" : "NETWORK_RANK_PRODUCTION_USER_REQUIRED");
        }
        return runtime == UserAuthEnvironment.SANDBOX
                ? new Scope(true, false, "SANDBOX", runId) : new Scope(false, false, "PRODUCTION", "");
    }

    private record Scope(boolean sandbox, boolean development, String environment, String runId) { }
}
