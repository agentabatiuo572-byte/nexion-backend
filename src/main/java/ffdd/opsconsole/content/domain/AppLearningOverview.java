package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;
import java.util.List;

public record AppLearningOverview(
        List<AppLearningCourseView> courses,
        int completedCourses,
        int totalCourses,
        BigDecimal earnedNex) {
}
