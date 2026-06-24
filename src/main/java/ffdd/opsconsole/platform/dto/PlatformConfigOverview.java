package ffdd.opsconsole.platform.dto;

import java.util.List;
import java.util.Map;

public record PlatformConfigOverview(
        List<Map<String, Object>> featureFlags,
        List<Map<String, Object>> killSwitches,
        List<Map<String, Object>> systemHealth,
        Map<String, Object> stats) {
}
