package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record LearningProgressRow(
        String courseId,
        String courseVersion,
        int progressPct,
        int attempts,
        LocalDateTime completedAt) {
}
