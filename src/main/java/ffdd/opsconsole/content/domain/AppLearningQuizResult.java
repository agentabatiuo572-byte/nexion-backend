package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record AppLearningQuizResult(
        String courseId,
        String version,
        int score,
        boolean passed,
        boolean completed,
        boolean rewardGranted,
        BigDecimal rewardNex,
        int attempts) {
}
