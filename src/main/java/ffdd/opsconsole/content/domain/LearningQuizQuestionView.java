package ffdd.opsconsole.content.domain;

import java.util.List;

public record LearningQuizQuestionView(
        String questionId,
        String questionZh,
        String questionEn,
        List<String> optionsZh,
        List<String> optionsEn,
        int correctOptionIndex,
        String questionVi,
        List<String> optionsVi) {

    public LearningQuizQuestionView(String questionId, String questionZh, String questionEn,
            List<String> optionsZh, List<String> optionsEn, int correctOptionIndex) {
        this(questionId, questionZh, questionEn, optionsZh, optionsEn, correctOptionIndex, questionEn, optionsEn);
    }
}
