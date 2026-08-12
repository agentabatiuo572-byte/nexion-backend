package ffdd.opsconsole.content.dto;

import java.util.List;

public record AppLearningQuizSubmitRequest(List<Integer> answers, String idempotencyKey, String expectedVersion) {
    public AppLearningQuizSubmitRequest(List<Integer> answers, String idempotencyKey) {
        this(answers, idempotencyKey, null);
    }
}
