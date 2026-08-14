package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppNetworkRegionMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppNetworkRegionService {
    private final AppNetworkRegionMapper mapper;
    private final Clock clock;

    public ApiResult<Map<String, Object>> list(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        List<AppNetworkRegionMapper.RegionRow> rows = mapper.regions(userId);
        List<Map<String, Object>> regions = (rows == null ? List.<AppNetworkRegionMapper.RegionRow>of() : rows)
                .stream().map(this::region).toList();
        long activeNodes = regions.stream().mapToLong(row -> (Long) row.get("activeNodes")).sum();
        long activeJobs = regions.stream().mapToLong(row -> (Long) row.get("activeJobs")).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeNodes", activeNodes);
        result.put("activeJobs", activeJobs);
        result.put("regions", regions);
        result.put("source", "server");
        result.put("generatedAt", clock.instant().toString());
        return ApiResult.ok(result);
    }

    private Map<String, Object> region(AppNetworkRegionMapper.RegionRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.id());
        result.put("regionLabel", row.regionLabel());
        result.put("location", row.location());
        result.put("displayName", row.displayName());
        result.put("activeNodes", Math.max(0L, value(row.activeNodes())));
        result.put("activeJobs", Math.max(0L, value(row.activeJobs())));
        result.put("jobsPerHour", Math.max(0L, value(row.jobsPerHour())));
        result.put("latitude", row.latitude());
        result.put("longitude", row.longitude());
        result.put("isUserRegion", Integer.valueOf(1).equals(row.userRegion()));
        return result;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
