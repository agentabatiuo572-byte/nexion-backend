package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import java.time.LocalDateTime;

public record LearningCourseVersionView(
        String courseId,
        String version,
        String status,
        LearningCourseUpsertRequest payload,
        long revision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
