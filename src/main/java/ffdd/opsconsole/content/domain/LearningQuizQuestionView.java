package ffdd.opsconsole.content.domain;

import java.util.List;
import java.util.Map;

public record LearningQuizQuestionView(
        String questionId,
        String questionZh,
        String questionEn,
        List<String> optionsZh,
        List<String> optionsEn,
        int correctOptionIndex,
        String questionVi,
        List<String> optionsVi,
        Map<String, String> questionTranslations, Map<String, List<String>> optionTranslations) {
    public LearningQuizQuestionView(
        String questionId,
        String questionZh,
        String questionEn,
        List<String> optionsZh,
        List<String> optionsEn,
        int correctOptionIndex,
        String questionVi,
        List<String> optionsVi) {
        this(questionId, questionZh, questionEn, optionsZh, optionsEn, correctOptionIndex, questionVi, optionsVi, null, null);
    }

    public LearningQuizQuestionView(String questionId, String questionZh, String questionEn,
            List<String> optionsZh, List<String> optionsEn, int correctOptionIndex) {
        this(questionId, questionZh, questionEn, optionsZh, optionsEn, correctOptionIndex, questionEn, optionsEn);
    }
}
