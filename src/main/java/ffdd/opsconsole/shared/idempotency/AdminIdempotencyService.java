package ffdd.opsconsole.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminIdempotencyService {
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    /** Must stay aligned with nx_admin_idempotency_record.idempotency_key VARCHAR(128). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";

    private final AdminIdempotencyRecordMapper recordMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public <T> T execute(String scope, String idempotencyKey, String requestHash, Class<T> responseType, Supplier<T> action) {
        String normalizedScope = normalizeScope(scope);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String normalizedHash = normalizeRequired(requestHash, "IDEMPOTENCY_REQUEST_HASH_REQUIRED");

        AdminIdempotencyRecordEntity existing = recordMapper.selectActive(normalizedScope, normalizedKey);
        if (existing != null) {
            return replay(existing, normalizedHash, responseType);
        }

        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(DEFAULT_TTL);
        AdminIdempotencyRecordEntity record;
        if (recordMapper.resetExpired(normalizedScope, normalizedKey, normalizedHash, expiresAt) == 1) {
            record = recordMapper.selectActive(normalizedScope, normalizedKey);
            if (record == null) {
                throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IDEMPOTENCY_RECLAIM_CONFLICT");
            }
        } else {
            record = new AdminIdempotencyRecordEntity();
            record.setScope(normalizedScope);
            record.setIdempotencyKey(normalizedKey);
            record.setRequestHash(normalizedHash);
            record.setStatus(STATUS_PROCESSING);
            record.setExpiresAt(expiresAt);
            record.setIsDeleted(0);
            try {
                recordMapper.insert(record);
            } catch (DuplicateKeyException ex) {
                AdminIdempotencyRecordEntity duplicate = recordMapper.selectActive(normalizedScope, normalizedKey);
                if (duplicate != null) {
                    return replay(duplicate, normalizedHash, responseType);
                }
                throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IDEMPOTENCY_KEY_CONFLICT");
            }
        }

        try {
            T result = action.get();
            recordMapper.markSucceeded(record.getId(), writeJson(result));
            return result;
        } catch (RuntimeException ex) {
            recordMapper.markFailed(record.getId(), ex.getMessage());
            throw ex;
        }
    }

    private <T> T replay(AdminIdempotencyRecordEntity record, String requestHash, Class<T> responseType) {
        if (!requestHash.equals(record.getRequestHash())) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        }
        if (STATUS_SUCCEEDED.equals(record.getStatus()) && StringUtils.hasText(record.getResponseJson())) {
            return readJson(record.getResponseJson(), responseType);
        }
        throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IDEMPOTENCY_REQUEST_IN_PROGRESS");
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
}
