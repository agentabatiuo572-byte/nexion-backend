package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record LearningCourseView(
        String id,
        String title,
        String category,
        String format,
        String level,
        BigDecimal rewardNex,
        boolean featured,
        String duration,
        String version,
        String status,
        String body) {
}
