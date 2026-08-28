package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.mapper.AppAmbassadorPolicyMapper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Server-authoritative policy snapshot for the ambassador form. */
@Service
@RequiredArgsConstructor
public class AppAmbassadorPolicyService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> BUCKETS = Set.of("venue", "kol", "print", "dev");
    private static final String RUN_ID = "[A-Za-z0-9][A-Za-z0-9._-]{7,95}";

    private final AppAmbassadorPolicyMapper mapper;
    private final Environment environment;

    public ApiResult<Map<String, Object>> policy(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        AppAmbassadorPolicyMapper.UserScope user = mapper.user(userId);
        Scope scope = scope(userId, user);
        AppAmbassadorPolicyMapper.PolicyRow row = mapper.policy();
        if (!validRow(row)) return ApiResult.fail(503, "AMBASSADOR_POLICY_UNAVAILABLE");
        List<Map<String, Object>> buckets;
        try {
            buckets = JSON.readValue(row.bucketsJson(), new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return ApiResult.fail(503, "AMBASSADOR_POLICY_UNAVAILABLE");
        }
        if (!validBuckets(buckets)) return ApiResult.fail(503, "AMBASSADOR_POLICY_UNAVAILABLE");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("policyVersion", row.policyVersion());
        output.put("revision", row.revision());
        output.put("defaultBudgetUsdt", row.defaultBudgetUsdt());
        output.put("buckets", buckets);
        output.put("source", "server");
        output.put("sourceEnvironment", scope.sourceEnvironment());
        output.put("runId", scope.runId());
        return ApiResult.ok(output);
    }

    private boolean validRow(AppAmbassadorPolicyMapper.PolicyRow row) {
        return row != null && row.policyVersion() != null && !row.policyVersion().isBlank()
                && row.revision() != null && row.revision() > 0
                && row.defaultBudgetUsdt() != null && row.defaultBudgetUsdt().signum() > 0
                && row.defaultBudgetUsdt().compareTo(new BigDecimal("1000000")) <= 0
                && row.bucketsJson() != null && !row.bucketsJson().isBlank();
    }

    private boolean validBuckets(List<Map<String, Object>> buckets) {
        if (buckets == null || buckets.size() != BUCKETS.size()) return false;
        return buckets.stream().allMatch(row -> {
            if (row == null || !(row.get("id") instanceof String id) || !BUCKETS.contains(id)
                    || !(row.get("title") instanceof String title) || title.isBlank()
                    || !(row.get("range") instanceof String range) || range.isBlank()
                    || !(row.get("rule") instanceof String rule) || rule.isBlank()) return false;
            Object min = row.get("minBudgetUsdt");
            Object max = row.get("maxBudgetUsdt");
            return min instanceof Number && max instanceof Number
                    && Double.isFinite(((Number) min).doubleValue()) && Double.isFinite(((Number) max).doubleValue())
                    && ((Number) min).doubleValue() >= 100 && ((Number) max).doubleValue() >= ((Number) min).doubleValue()
                    && ((Number) max).doubleValue() <= 10000;
        }) && buckets.stream().map(row -> row.get("id")).distinct().count() == BUCKETS.size();
    }

    private Scope scope(Long userId, AppAmbassadorPolicyMapper.UserScope user) {
        if (user == null || user.sandbox() == null) throw new BizException(403, "AMBASSADOR_USER_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles() == null ? new String[0] : environment.getActiveProfiles())
                .map(value -> value.trim().toLowerCase()).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        boolean development = profiles.size() == 1 && "dev".equals(profiles.iterator().next());
        boolean isolated = profiles.size() == 1 && "test".equals(profiles.iterator().next());
        boolean production = profiles.isEmpty() || (profiles.size() == 1 && Set.of("prod").contains(profiles.iterator().next()));
        if (!development && !isolated && !production) throw new BizException(503, "AMBASSADOR_PROFILE_INVALID");
        if (development) {
            requireDevelopmentUser(userId, user);
            return new Scope("PRODUCTION", "");
        }
        if (isolated && user.sandbox() != 1) throw new BizException(403, "AMBASSADOR_SANDBOX_USER_REQUIRED");
        if (production && user.sandbox() != 0) throw new BizException(403, "AMBASSADOR_PRODUCTION_USER_REQUIRED");
        if (production) return new Scope("PRODUCTION", "");
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
        if (!runId.matches(RUN_ID)) throw new BizException(503, "AMBASSADOR_RUN_ID_REQUIRED");
        return new Scope("SANDBOX", runId);
    }

    private void requireDevelopmentUser(Long userId, AppAmbassadorPolicyMapper.UserScope user) {
        if (!Integer.valueOf(1).equals(user.sandbox())) throw new BizException(403, "AMBASSADOR_DEVELOPMENT_USER_REQUIRED");
        if (userId == null || userId <= 0) throw new BizException(403, "AMBASSADOR_DEVELOPMENT_USER_REQUIRED");
    }

    private record Scope(String sourceEnvironment, String runId) { }
}
