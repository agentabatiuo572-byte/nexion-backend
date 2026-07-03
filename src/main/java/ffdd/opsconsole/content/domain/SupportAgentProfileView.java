package ffdd.opsconsole.content.domain;

import java.util.List;

public record SupportAgentProfileView(
        String id,
        Long adminId,
        String name,
        String email,
        String adminRole,
        String status,
        String seatType,
        String position,
        List<String> serviceTypes,
        List<String> tags,
        Integer maxConcurrent,
        Boolean enabled,
        Boolean transferable,
        Boolean busy,
        Long assignedUserCount,
        String updatedAt) {
}
