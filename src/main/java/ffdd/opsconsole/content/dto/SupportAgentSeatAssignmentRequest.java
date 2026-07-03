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
        String assignmentType,
        String operator,
        String reason) {
}
