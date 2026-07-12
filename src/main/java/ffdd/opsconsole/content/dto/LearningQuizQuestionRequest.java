package ffdd.opsconsole.content.dto;

import java.util.List;

/** Structured quiz question; correctOptionIndex points into both locale option lists. */
public record LearningQuizQuestionRequest(
        String questionId,
        String questionZh,
        String questionEn,
        List<String> optionsZh,
        List<String> optionsEn,
        Integer correctOptionIndex,
        String questionVi,
        List<String> optionsVi) {

    public LearningQuizQuestionRequest(String questionId, String questionZh, String questionEn,
            List<String> optionsZh, List<String> optionsEn, Integer correctOptionIndex) {
        this(questionId, questionZh, questionEn, optionsZh, optionsEn, correctOptionIndex, questionEn, optionsEn);
    }
}
