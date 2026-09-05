package ffdd.opsconsole.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns the three durable idempotency transaction phases: claim, business action, and failure recording.
 * Keeping the claim committed before the action lets a failed action roll back without stranding its key in PROCESSING.
 */
@Service
@RequiredArgsConstructor
public class AdminIdempotencyTransactionExecutor {
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_UNKNOWN = "UNKNOWN";

    private final AdminIdempotencyRecordMapper recordMapper;
    private final ObjectMapper objectMapper;
    private final AdminIdempotencyExpiryTransitionExecutor expiryTransitionExecutor;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public <T> Claim<T> claimRetained(String scope, String idempotencyKey, String requestHash,
                                    LocalDateTime expiresAt, Class<T> responseType) {
        return claimInternal(scope, idempotencyKey, requestHash, expiresAt, responseType, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public <T> Claim<T> claim(
            String scope,
            String idempotencyKey,
            String requestHash,
            LocalDateTime expiresAt,
        Class<T> responseType) {
        return claimInternal(scope, idempotencyKey, requestHash, expiresAt, responseType, false);
    }

    private <T> Claim<T> claimInternal(String scope, String idempotencyKey, String requestHash,
                                      LocalDateTime expiresAt, Class<T> responseType, boolean retainSuccess) {
        AdminIdempotencyRecordEntity record = null;
        AdminIdempotencyRecordEntity existing = recordMapper.selectActive(scope, idempotencyKey);
        if (existing != null) {
            return resolveKnownRecord(existing, requestHash, expiresAt, responseType);
        }

        AdminIdempotencyRecordEntity current = record == null
                ? recordMapper.selectCurrent(scope, idempotencyKey)
                : null;
        if (retainSuccess && current != null
                && (STATUS_SUCCEEDED.equals(current.getStatus()) || STATUS_FAILED.equals(current.getStatus()))) {
            // Never recycle a retained success; FAILED retries use a status CAS,
            // so a concurrent completion cannot be overwritten by an expiry reset.
            return resolveKnownRecord(current, requestHash, expiresAt, responseType);
        } else if (record == null && current != null && STATUS_UNKNOWN.equals(current.getStatus())) {
            throw conflict("IDEMPOTENCY_RESULT_UNKNOWN");
        } else if (record == null && current != null && STATUS_PROCESSING.equals(current.getStatus())
                && Integer.valueOf(0).equals(current.getIsDeleted())) {
            // Database NOW() is the sole lease clock. The independent CAS is
            // deliberately attempted even when this JVM has a different zone.
            int transitioned = expiryTransitionExecutor.markCurrentExpiredProcessingUnknown(
                    current.getId(), scope, idempotencyKey);
            if (transitioned == 1) {
                throw conflict("IDEMPOTENCY_RESULT_UNKNOWN");
            }
            AdminIdempotencyRecordEntity refreshed = expiryTransitionExecutor.loadCurrentCommitted(
                    scope, idempotencyKey);
            if (refreshed == null || STATUS_UNKNOWN.equals(refreshed.getStatus())) {
                throw conflict("IDEMPOTENCY_RESULT_UNKNOWN");
            }
            // A different instance may have reached a known terminal state
            // while our CAS raced. Preserve that state rather than degrading
            // it to UNKNOWN; PROCESSING still responds as in-progress.
            return resolveKnownRecord(refreshed, requestHash, expiresAt, responseType);
        } else if (record == null && current != null
                && recordMapper.resetExpiredById(current.getId(), requestHash, expiresAt) == 1) {
            record = current;
        } else if (record == null && current != null) {
            AdminIdempotencyRecordEntity raced = recordMapper.selectActive(scope, idempotencyKey);
            if (raced != null) {
                return Claim.replayed(replay(raced, requestHash, responseType));
            }
            throw conflict("IDEMPOTENCY_RECLAIM_CONFLICT");
        } else if (record == null) {
            record = new AdminIdempotencyRecordEntity();
            record.setScope(scope);
            record.setIdempotencyKey(idempotencyKey);
            record.setRequestHash(requestHash);
            record.setStatus(STATUS_PROCESSING);
            record.setExpiresAt(expiresAt);
            record.setIsDeleted(0);
            try {
                recordMapper.insert(record);
            } catch (DuplicateKeyException ex) {
                // A locking current read sees the concurrent winner even under MySQL REPEATABLE READ.
                AdminIdempotencyRecordEntity duplicate =
                        recordMapper.selectActiveForUpdate(scope, idempotencyKey);
                if (duplicate != null) {
                    return Claim.replayed(replay(duplicate, requestHash, responseType));
                }
                throw conflict("IDEMPOTENCY_KEY_CONFLICT");
            }
        }
        return Claim.claimed(record.getId());
    }

    // Mutation code deliberately takes row mutexes after reading gates/configuration.
    // READ_COMMITTED prevents those earlier reads from pinning a stale snapshot while
    // a concurrent command is waiting on the same mutex.
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public <T> T runClaimed(Long recordId, Supplier<T> action) {
        T result = action.get();
        if (recordMapper.markSucceeded(recordId, writeJson(result)) != 1) {
            throw conflict("IDEMPOTENCY_SUCCESS_STATE_LOST");
        }
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(Long recordId, String errorMessage) {
        if (recordMapper.markFailed(recordId, errorMessage) != 1) {
            throw conflict("IDEMPOTENCY_FAILURE_STATE_LOST");
        }
    }

    private <T> Claim<T> resolveKnownRecord(
            AdminIdempotencyRecordEntity record,
            String requestHash,
            LocalDateTime expiresAt,
            Class<T> responseType) {
        if (!STATUS_FAILED.equals(record.getStatus())) {
            return Claim.replayed(replay(record, requestHash, responseType));
        }
        if (!requestHash.equals(record.getRequestHash())) {
            return Claim.replayed(replay(record, requestHash, responseType));
        }
        if (recordMapper.resetFailedById(record.getId(), requestHash, expiresAt) == 1) {
            return Claim.claimed(record.getId());
        }
        AdminIdempotencyRecordEntity raced = recordMapper.selectActive(
                record.getScope(), record.getIdempotencyKey());
        if (raced != null) {
            return Claim.replayed(replay(raced, requestHash, responseType));
        }
        throw conflict("IDEMPOTENCY_RETRY_CONFLICT");
    }

    private <T> T replay(AdminIdempotencyRecordEntity record, String requestHash, Class<T> responseType) {
        if (!requestHash.equals(record.getRequestHash())) {
            throw conflict("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        }
        if (STATUS_SUCCEEDED.equals(record.getStatus()) && StringUtils.hasText(record.getResponseJson())) {
            return readJson(record.getResponseJson(), responseType);
        }
        if (STATUS_FAILED.equals(record.getStatus())) {
            throw conflict("IDEMPOTENCY_PREVIOUS_REQUEST_FAILED");
        }
        if (STATUS_UNKNOWN.equals(record.getStatus())) {
            throw conflict("IDEMPOTENCY_RESULT_UNKNOWN");
        }
        throw conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "IDEMPOTENCY_RESPONSE_SERIALIZE_FAILED");
        }
    }

    private <T> T readJson(String value, Class<T> responseType) {
        try {
            return objectMapper.readValue(value, responseType);
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "IDEMPOTENCY_RESPONSE_DESERIALIZE_FAILED");
        }
    }

    private BizException conflict(String message) {
        return new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), message);
    }

    public record Claim<T>(boolean executeAction, Long recordId, T replayResponse) {
        private static <T> Claim<T> claimed(Long recordId) {
            return new Claim<>(true, recordId, null);
        }

        private static <T> Claim<T> replayed(T response) {
            return new Claim<>(false, null, response);
        }
    }
}
