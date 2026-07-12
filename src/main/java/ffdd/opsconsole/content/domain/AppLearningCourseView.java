package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;
import java.util.List;

public record AppLearningCourseView(
        String id,
        String title,
        String body,
        String category,
        String format,
        String level,
        String duration,
        BigDecimal rewardNex,
        boolean featured,
        String version,
        int progress,
        boolean completed,
        List<Question> questions) {

    public record Question(String questionId, String question, List<String> options) {
    }
}
