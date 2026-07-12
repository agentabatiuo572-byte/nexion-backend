package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;
import java.util.List;

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
        String body,
        String titleZh,
        String titleEn,
        String bodyZh,
        String bodyEn,
        List<LearningQuizQuestionView> quizQuestions,
        Integer passScore,
        Integer retryLimit,
        String completionCondition,
        String rewardEvent,
        long revision,
        String titleVi,
        String bodyVi) {

    public LearningCourseView(
            String id, String title, String category, String format, String level, BigDecimal rewardNex,
            boolean featured, String duration, String version, String status, String body,
            String titleZh, String titleEn, String bodyZh, String bodyEn, List<LearningQuizQuestionView> quizQuestions,
            Integer passScore, Integer retryLimit, String completionCondition, String rewardEvent, long revision) {
        this(id, title, category, format, level, rewardNex, featured, duration, version, status, body,
                titleZh, titleEn, bodyZh, bodyEn, quizQuestions, passScore, retryLimit, completionCondition, rewardEvent,
                revision, titleEn, bodyEn);
    }

    public LearningCourseView(
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
        this(id, title, category, format, level, rewardNex, featured, duration, version, status, body,
                title, "", body, "", List.of(), null, null, "", "", 0L, "", "");
    }
}
