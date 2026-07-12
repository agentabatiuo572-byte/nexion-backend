package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;
import java.util.List;

public record LearningCourseUpsertRequest(
        String titleZh,
        String titleEn,
        String bodyZh,
        String bodyEn,
        String category,
        String format,
        String difficulty,
        BigDecimal rewardNex,
        String duration,
        String publishState,
        String operator,
        String reason,
        List<LearningQuizQuestionRequest> quizQuestions,
        Integer passScore,
        Integer retryLimit,
        String completionCondition,
        String rewardEvent,
        Long expectedRevision,
        String titleVi,
        String bodyVi,
        String version) {

    public LearningCourseUpsertRequest(
            String titleZh, String titleEn, String bodyZh, String bodyEn, String category, String format,
            String difficulty, BigDecimal rewardNex, String duration, String publishState, String operator, String reason,
            List<LearningQuizQuestionRequest> quizQuestions, Integer passScore, Integer retryLimit,
            String completionCondition, String rewardEvent, Long expectedRevision) {
        this(titleZh, titleEn, bodyZh, bodyEn, category, format, difficulty, rewardNex, duration, publishState,
                operator, reason, quizQuestions, passScore, retryLimit, completionCondition, rewardEvent, expectedRevision,
                titleEn, bodyEn, null);
    }

    public LearningCourseUpsertRequest(
            String titleZh,
            String titleEn,
            String bodyZh,
            String bodyEn,
            String category,
            String format,
            String difficulty,
            BigDecimal rewardNex,
            String duration,
            String publishState,
            String operator,
            String reason) {
        this(titleZh, titleEn, bodyZh, bodyEn, category, format, difficulty, rewardNex,
                duration, publishState, operator, reason, List.of(), null, null, null, null, null, titleEn, bodyEn);
    }

    public LearningCourseUpsertRequest(
            String titleZh, String titleEn, String bodyZh, String bodyEn, String category, String format,
            String difficulty, BigDecimal rewardNex, String duration, String publishState, String operator, String reason,
            List<LearningQuizQuestionRequest> quizQuestions, Integer passScore, Integer retryLimit,
            String completionCondition, String rewardEvent, Long expectedRevision, String titleVi, String bodyVi) {
        this(titleZh, titleEn, bodyZh, bodyEn, category, format, difficulty, rewardNex, duration, publishState,
                operator, reason, quizQuestions, passScore, retryLimit, completionCondition, rewardEvent, expectedRevision,
                titleVi, bodyVi, null);
    }
}
