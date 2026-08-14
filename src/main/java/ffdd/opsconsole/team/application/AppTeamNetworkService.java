package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.mapper.AppTeamNetworkMapper;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppTeamNetworkService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final AppTeamNetworkMapper mapper;

    public ApiResult<Map<String, Object>> snapshot(Long userId) {
        List<AppTeamNetworkMapper.MemberRow> rows = mapper.members(userId);
        if (rows == null) {
            rows = List.of();
        }
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
            item.put("leg", row.leg()); item.put("sponsorId", row.sponsorUserId() == null ? null : String.valueOf(row.sponsorUserId()));
            item.put("joinedAt", row.joinedAt().atZone(BUSINESS_ZONE).toInstant().toString());
            item.put("monthVolumeUsdt", monthValue); item.put("lifetimeVolumeUsdt", null);
            item.put("status", row.status()); item.put("region", row.region()); members.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMembers", members.size()); result.put("directMembers", direct); result.put("activeMembers", active);
        result.put("monthVolumeUsdt", month); result.put("lifetimeVolumeUsdt", null); result.put("members", members);
        result.put("source", "server"); result.put("generatedAt", java.time.Instant.now().toString());
        return ApiResult.ok(result);
    }
    private BigDecimal zero(BigDecimal value) { return value == null || value.signum() < 0 ? BigDecimal.ZERO : value; }
    private String fallback(String value, String replacement) { return value == null || value.isBlank() ? replacement : value.trim(); }
    private int rank(String value) { try { return Integer.parseInt(fallback(value, "V0").replaceFirst("^[Vv]", "")); } catch (RuntimeException ignored) { return 0; } }
}
