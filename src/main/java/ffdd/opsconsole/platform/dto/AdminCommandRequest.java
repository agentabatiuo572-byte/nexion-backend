package ffdd.opsconsole.platform.dto;

import java.util.Map;

public record AdminCommandRequest(
        String domain,
        String action,
        String resourceType,
        String resourceId,
        String operator,
        String reason,
        String paramKey,
        String paramValue,
        Map<String, Object> payload) {
}
