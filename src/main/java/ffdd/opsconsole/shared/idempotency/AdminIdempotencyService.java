package ffdd.opsconsole.shared.idempotency;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminIdempotencyService {
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    /** Must stay aligned with nx_admin_idempotency_record.idempotency_key VARCHAR(128). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    /** Must stay aligned with nx_admin_idempotency_record.error_message VARCHAR(512). */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;
    private final AdminIdempotencyTransactionExecutor transactionExecutor;
    private final Clock clock;

    public <T> T execute(String scope, String idempotencyKey, String requestHash, Class<T> responseType, Supplier<T> action) {
        String normalizedScope = normalizeScope(scope);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String normalizedHash = normalizeRequired(requestHash, "IDEMPOTENCY_REQUEST_HASH_REQUIRED");

        LocalDateTime expiresAt = LocalDateTime.now(clock)
                .truncatedTo(ChronoUnit.SECONDS)
                .plus(DEFAULT_TTL);
        AdminIdempotencyTransactionExecutor.Claim<T> claim = transactionExecutor.claim(
                normalizedScope, normalizedKey, normalizedHash, expiresAt, responseType);
        if (!claim.executeAction()) {
            return claim.replayResponse();
        }

        try {
            return transactionExecutor.runClaimed(claim.recordId(), action);
        } catch (RuntimeException ex) {
            try {
                transactionExecutor.markFailed(claim.recordId(), errorSummary(ex));
            } catch (RuntimeException markFailedError) {
                // Preserve the business/SQL root cause; persistence of failure metadata must never mask it.
                ex.addSuppressed(markFailedError);
            }
            throw ex;
        }
    }

    private String errorSummary(RuntimeException ex) {
        String value = ex.getClass().getSimpleName() + (StringUtils.hasText(ex.getMessage()) ? ": " + ex.getMessage().trim() : "");
        if (value.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_MESSAGE_LENGTH - 1) + "…";
    }

    private String normalizeScope(String scope) {
        String normalized = normalizeRequired(scope, "IDEMPOTENCY_SCOPE_REQUIRED")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_.:-]", "_");
        if (normalized.length() > 96) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IDEMPOTENCY_SCOPE_TOO_LONG");
        }
        return normalized;
    }

    private String normalizeIdempotencyKey(String value) {
        String normalized = normalizeRequired(value, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IDEMPOTENCY_KEY_INVALID");
        }
        return normalized;
    }

    private String normalizeRequired(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), message);
        }
        return value.trim();
    }

}
