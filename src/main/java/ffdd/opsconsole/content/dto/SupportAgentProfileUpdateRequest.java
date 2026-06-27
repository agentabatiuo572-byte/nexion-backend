package ffdd.opsconsole.content.dto;

import java.util.List;

public record SupportAgentProfileUpdateRequest(
        String position,
        List<String> serviceTypes,
        List<String> tags,
        Integer maxConcurrent,
        Boolean enabled,
        Boolean transferable,
        Boolean busy,
        String operator,
        String reason) {
}
