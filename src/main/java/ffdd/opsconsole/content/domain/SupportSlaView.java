package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record SupportSlaView(
        String category,
        Integer firstResponseMins,
        Integer resolutionHours,
        String queue,
        String escalation,
        LocalDateTime updatedAt) {
}
