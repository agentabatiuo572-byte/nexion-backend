package ffdd.opsconsole.content.dto;

import java.util.List;
import java.util.Map;

/** Structured quiz question; correctOptionIndex points into both locale option lists. */
public record LearningQuizQuestionRequest(
        String questionId,
        String questionZh,
        String questionEn,
        List<String> optionsZh,
        List<String> optionsEn,
        Integer correctOptionIndex,
        String questionVi,
        List<String> optionsVi,
        Map<String, String> questionTranslations, Map<String, List<String>> optionTranslations) {
    public LearningQuizQuestionRequest(
        String questionId,
        String questionZh,
        String questionEn,
        List<String> optionsZh,
        List<String> optionsEn,
        Integer correctOptionIndex,
        String questionVi,
        List<String> optionsVi) {
        this(questionId, questionZh, questionEn, optionsZh, optionsEn, correctOptionIndex, questionVi, optionsVi, null, null);
    }

    public LearningQuizQuestionRequest(String questionId, String questionZh, String questionEn,
            List<String> optionsZh, List<String> optionsEn, Integer correctOptionIndex) {
        this(questionId, questionZh, questionEn, optionsZh, optionsEn, correctOptionIndex, questionEn, optionsEn);
    }
}
