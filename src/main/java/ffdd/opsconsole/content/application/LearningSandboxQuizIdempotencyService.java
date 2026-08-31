package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.AppLearningQuizResult;
import ffdd.opsconsole.content.domain.LearningSandboxIdempotencyRow;
import ffdd.opsconsole.content.domain.LearningQuizReceipt;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Isolated idempotency receipt for sandbox learning attempts. */
@Service
@RequiredArgsConstructor
public class LearningSandboxQuizIdempotencyService {
    private final AppLearningMapper learningMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public AppLearningQuizResult execute(String runId, Long userId, String courseId, String courseVersion,
                                         String requestHash, String idempotencyKey,
                                         Supplier<AppLearningQuizResult> action) {
        if (learningMapper.claimSandboxQuizIdempotency(runId, userId, courseId, courseVersion, idempotencyKey, requestHash) == 1) {
            AppLearningQuizResult result = action.get();
            if (learningMapper.completeSandboxQuizIdempotency(
                    runId, userId, courseId, courseVersion, idempotencyKey, "COMPLETED", write(result)) != 1) {
                throw new IllegalStateException("LEARNING_SANDBOX_IDEMPOTENCY_RECEIPT_WRITE_FAILED");
            }
            return result;
        }
        LearningSandboxIdempotencyRow stored = learningMapper.lockSandboxQuizIdempotency(
                runId, userId, courseId, courseVersion, idempotencyKey);
        if (stored == null) throw new BizException(409, "LEARNING_QUIZ_IDEMPOTENCY_RETRY_REQUIRED");
        if (!requestHash.equals(stored.requestHash())) throw new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        if (!"COMPLETED".equals(stored.status()) || stored.resultJson() == null) {
            throw new BizException(409, "LEARNING_QUIZ_IDEMPOTENCY_RETRY_REQUIRED");
        }
        return read(stored.resultJson());
    }

    public LearningQuizReceipt receipt(String runId, Long userId, String courseId, String courseVersion, String idempotencyKey) {
        LearningSandboxIdempotencyRow stored = learningMapper.lockSandboxQuizIdempotency(
                runId, userId, courseId, courseVersion, idempotencyKey);
        if (stored == null) {
            return new LearningQuizReceipt("ABSENT", false, null, null);
        }
        if ("PENDING".equals(stored.status())) {
            return new LearningQuizReceipt("PENDING", false, stored.requestHash(), null);
        }
        if ("COMPLETED".equals(stored.status()) && stored.resultJson() != null) {
            return new LearningQuizReceipt("COMMITTED", true, stored.requestHash(), read(stored.resultJson()));
        }
        throw new IllegalStateException("LEARNING_SANDBOX_IDEMPOTENCY_RECEIPT_INVALID");
    }

    private String write(AppLearningQuizResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("LEARNING_SANDBOX_IDEMPOTENCY_SERIALIZATION_FAILED", ex);
        }
    }

    private AppLearningQuizResult read(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, AppLearningQuizResult.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("LEARNING_SANDBOX_IDEMPOTENCY_RECEIPT_INVALID", ex);
        }
    }
}
