package ffdd.opsconsole.content.domain;

import java.util.List;

public record SupportAgentProfileRecord(
        Long adminId,
        String position,
        List<String> serviceTypes,
        List<String> tags,
        Integer maxConcurrent,
        Boolean enabled,
        Boolean transferable,
        Boolean busy,
        String updatedAt) {
}
