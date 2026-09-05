package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.mapper.AppTeamNetworkMapper;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppTeamNetworkService {
    private static final int MAX_NETWORK_MEMBERS = 500;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private final AppTeamNetworkMapper mapper;
    private final Environment environment;

    public ApiResult<Map<String, Object>> snapshot(Long userId) { return snapshot(userId, 0L); }

    /** Counters describe this page; callers accumulate until nextCursor is null. */
    public ApiResult<Map<String, Object>> snapshot(Long userId, long afterId) {
        if (afterId < 0) throw new BizException(422, "TEAM_NETWORK_CURSOR_INVALID");
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        AppTeamNetworkMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "TEAM_USER_REQUIRED");
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        boolean developmentRuntime = false;
        boolean sandboxRuntime = profiles.length == 1 && "test".equals(normalize(profiles[0]));
        boolean productionRuntime = profiles == null || profiles.length == 0
                || (profiles.length == 1 && java.util.Set.of("dev", "prod").contains(normalize(profiles[0])));
        if (!developmentRuntime && !sandboxRuntime && !productionRuntime) throw new BizException(503, "TEAM_PROFILE_INVALID");
        if (sandboxRuntime && user.sandbox() != 1) throw new BizException(403, "TEAM_SANDBOX_USER_REQUIRED");
        if (productionRuntime && user.sandbox() != 0) throw new BizException(403, "TEAM_PRODUCTION_USER_REQUIRED");
        if (sandboxRuntime) {
            String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
            if (!RUN_ID.matcher(runId).matches()) throw new BizException(503, "TEAM_RUN_ID_REQUIRED");
            return ApiResult.ok(TeamSandboxFactGenerator.network(runId, userId));
        }
        List<AppTeamNetworkMapper.MemberRow> rows = mapper.membersPage(userId, afterId, MAX_NETWORK_MEMBERS + 1);
        if (rows == null) {
            rows = List.of();
        }
        boolean hasMore = rows.size() > MAX_NETWORK_MEMBERS;
        if (hasMore) rows = rows.subList(0, MAX_NETWORK_MEMBERS);
        String nextCursor = hasMore ? String.valueOf(rows.get(rows.size() - 1).memberUserId()) : null;
        List<Map<String, Object>> members = new ArrayList<>();
        BigDecimal month = BigDecimal.ZERO;
        int direct = 0; int active = 0;
        for (var row : rows) {
            BigDecimal monthValue = zero(row.monthVolumeUsdt());
            month = month.add(monthValue);
            if (row.level() != null && row.level() == 1) direct++;
            if ("ACTIVE".equals(row.status())) active++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(row.memberUserId())); item.put("name", fallback(row.nickname(), "Member " + row.memberUserId()));
            item.put("avatarUrl", row.avatarUrl()); item.put("vRank", rank(row.vRank())); item.put("layer", row.level());
            item.put("leg", row.leg());
            item.put("joinedAt", row.joinedAt() == null ? null : row.joinedAt().atZone(BUSINESS_ZONE).toInstant().toString());
            item.put("monthVolumeUsdt", monthValue); item.put("lifetimeVolumeUsdt", null);
            item.put("status", row.status()); item.put("region", row.region()); members.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nextCursor", nextCursor);
        result.put("totalMembers", members.size()); result.put("directMembers", direct); result.put("activeMembers", active);
        result.put("monthVolumeUsdt", month); result.put("lifetimeVolumeUsdt", null); result.put("members", members);
        result.put("source", "server"); result.put("sourceEnvironment", "PRODUCTION");
        result.put("runId", ""); result.put("serverCanonical", true);
        result.put("generatedAt", java.time.Instant.now().toString());
        return ApiResult.ok(result);
    }
    private BigDecimal zero(BigDecimal value) { return value == null || value.signum() < 0 ? BigDecimal.ZERO : value; }
    private String fallback(String value, String replacement) { return value == null || value.isBlank() ? replacement : value.trim(); }
    private int rank(String value) { try { return Integer.parseInt(fallback(value, "V0").replaceFirst("^[Vv]", "")); } catch (RuntimeException ignored) { return 0; } }
    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private void requireDevelopmentUser(Long userId, Integer sandbox) {
        if (!Integer.valueOf(1).equals(sandbox)) throw new BizException(403, "TEAM_DEVELOPMENT_USER_REQUIRED");
    }

}
