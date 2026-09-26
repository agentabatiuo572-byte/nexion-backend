package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AppLearningOverview(
        List<AppLearningCourseView> courses,
        int completedCourses,
        int totalCourses,
        BigDecimal earnedNex,
        boolean serverCanonical,
        String sourceEnvironment,
        String runId,
        Map<String, String> rewardTitles) {
}
