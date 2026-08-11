package ffdd.opsconsole.content.dto;

import java.util.List;

public record SupportAgentSeatAssignmentRequest(
        String position,
        List<String> serviceTypes,
        List<String> tags,
        Integer maxConcurrent,
        Boolean enabled,
        Boolean transferable,
        Boolean busy,
        List<Long> userIds,
        Long expectedVersion,
        String operator,
        String reason) {
    public SupportAgentSeatAssignmentRequest(
            String position, List<String> serviceTypes, List<String> tags, Integer maxConcurrent,
            Boolean enabled, Boolean transferable, Boolean busy, List<Long> userIds, String operator, String reason) {
        this(position, serviceTypes, tags, maxConcurrent, enabled, transferable, busy, userIds, 1L, operator, reason);
    }
}
