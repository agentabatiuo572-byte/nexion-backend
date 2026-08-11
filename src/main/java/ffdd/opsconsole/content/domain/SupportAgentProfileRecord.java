package ffdd.opsconsole.content.domain;

import java.util.List;

public record SupportAgentProfileRecord(
        Long adminId,
        String seatType,
        String position,
        List<String> serviceTypes,
        List<String> tags,
        Integer maxConcurrent,
        Boolean enabled,
        Boolean transferable,
        Boolean busy,
        Long version,
        String updatedAt) {
    public SupportAgentProfileRecord(
            Long adminId, String seatType, String position, List<String> serviceTypes, List<String> tags,
            Integer maxConcurrent, Boolean enabled, Boolean transferable, Boolean busy, String updatedAt) {
        this(adminId, seatType, position, serviceTypes, tags, maxConcurrent, enabled, transferable, busy, 1L, updatedAt);
    }
}
