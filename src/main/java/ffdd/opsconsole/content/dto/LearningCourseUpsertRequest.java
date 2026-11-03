package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
        String version,
        Map<String, String> titleTranslations, Map<String, String> bodyTranslations) {
    public LearningCourseUpsertRequest withPreviousTranslations(LearningCourseUpsertRequest previous) {
        Map<String, LearningQuizQuestionRequest> questionsById = new java.util.HashMap<>();
        if (previous.quizQuestions() != null) previous.quizQuestions().forEach(q -> questionsById.put(q.questionId(), q));
        List<LearningQuizQuestionRequest> merged = quizQuestions == null ? null : quizQuestions.stream().map(q -> {
            var prior = questionsById.get(q.questionId().trim());
            if (prior == null) return q;
            return new LearningQuizQuestionRequest(q.questionId(), q.questionZh(), q.questionEn(), q.optionsZh(),
                    q.optionsEn(), q.correctOptionIndex(), q.questionVi(), q.optionsVi(),
                    q.questionTranslations() == null ? prior.questionTranslations() : q.questionTranslations(),
                    q.optionTranslations() == null ? prior.optionTranslations() : q.optionTranslations());
        }).toList();
        return new LearningCourseUpsertRequest(titleZh, titleEn, bodyZh, bodyEn, category, format, difficulty,
                rewardNex, duration, publishState, operator, reason, merged, passScore, retryLimit, completionCondition,
                rewardEvent, expectedRevision, titleVi, bodyVi, version,
                titleTranslations == null ? previous.titleTranslations() : titleTranslations,
                bodyTranslations == null ? previous.bodyTranslations() : bodyTranslations);
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
        this(titleZh, titleEn, bodyZh, bodyEn, category, format, difficulty, rewardNex, duration, publishState, operator, reason, quizQuestions, passScore, retryLimit, completionCondition, rewardEvent, expectedRevision, titleVi, bodyVi, version, null, null);
    }

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
