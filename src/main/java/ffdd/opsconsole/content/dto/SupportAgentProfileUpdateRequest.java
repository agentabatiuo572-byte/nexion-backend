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
        Long expectedVersion,
        String operator,
        String reason) {
    public SupportAgentProfileUpdateRequest(
            String position, List<String> serviceTypes, List<String> tags, Integer maxConcurrent,
            Boolean enabled, Boolean transferable, Boolean busy, String operator, String reason) {
        this(position, serviceTypes, tags, maxConcurrent, enabled, transferable, busy, 1L, operator, reason);
    }
}
