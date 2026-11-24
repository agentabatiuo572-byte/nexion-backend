package ffdd.opsconsole.shared.idempotency;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminIdempotencyService {
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    /** Must stay aligned with nx_admin_idempotency_record.idempotency_key VARCHAR(128). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private final AdminIdempotencyTransactionExecutor transactionExecutor;
    private final Clock clock;

    public <T> T execute(String scope, String idempotencyKey, String requestHash, Class<T> responseType, Supplier<T> action) {
        return execute(scope, idempotencyKey, requestHash, responseType, action, false);
    }

    /** For commands that lock a bounded evidence range and must reject phantom facts. */
    public <T> T executeRepeatableRead(String scope, String idempotencyKey, String requestHash, Class<T> responseType, Supplier<T> action) {
        return execute(scope, idempotencyKey, requestHash, responseType, action, false, true);
    }

    /** Financial intents can be recovered long after the processing lease expires. */
    public <T> T executeRetained(String scope, String idempotencyKey, String requestHash, Class<T> responseType, Supplier<T> action) {
        return execute(scope, idempotencyKey, requestHash, responseType, action, true);
    }

    /** The action owns independent commits; failure to retain its receipt must never authorize another execution. */
    public <T> T executeRetainedIndependent(String scope, String key, String requestHash, Class<T> responseType, Supplier<T> action) {
        return execute(scope, key, requestHash, responseType, action, true, false, AdminCommandOrigin.current(scope), true);
    }

    /** Retain the financial result while fencing a bounded, shared evidence range. */
    public <T> T executeRetainedRepeatableRead(String scope, String idempotencyKey, String requestHash, Class<T> responseType, Supplier<T> action) {
        return execute(scope, idempotencyKey, requestHash, responseType, action, true, true);
    }

    /** Typed collection commands supply an exact business target, never a collection-wide fence. */
    public <T> T executeBound(String scope, String key, String requestHash, Class<T> responseType,
                              String readAuthority, String target, Supplier<T> action) {
        return execute(scope, key, requestHash, responseType, action, true, false,
                AdminCommandOrigin.current(scope, readAuthority, target));
    }

    public <T> T executeBoundTargets(String scope, String key, String requestHash, Class<T> responseType,
                                     String readAuthority, java.util.List<AdminCommandOrigin.Target> targets,
                                     Supplier<T> action) {
        return execute(scope, key, requestHash, responseType, action, true, false,
                AdminCommandOrigin.current(scope, readAuthority, null, targets));
    }

    /** A2 decision commands store their authenticated submitter even though A2 has no read authority. */
    public <T> T executeOwned(String scope, String key, String requestHash, Class<T> responseType,
                              long actorId, java.util.List<AdminCommandOrigin.Target> targets,
                              Supplier<T> action) {
        AdminCommandOrigin origin = new AdminCommandOrigin(actorId, null, null, null, null, targets);
        return execute(scope, key, requestHash, responseType, action, true, false, origin, false);
    }

    /** A2 decision commands keep their trusted origin read authority and targets when the proposal provenance is known. */
    public <T> T executeOwned(String scope, String key, String requestHash, Class<T> responseType,
                              long actorId, String readAuthority, java.util.List<AdminCommandOrigin.Target> targets,
                              Supplier<T> action) {
        AdminCommandOrigin origin = new AdminCommandOrigin(actorId, null, null, null, readAuthority, targets);
        return execute(scope, key, requestHash, responseType, action, true, false, origin, false);
    }


    /** Read-only recovery information. Callers must derive the scope from the authenticated actor. */
    public RecoveryStatus recoveryStatus(String scope, String idempotencyKey, String requestHash) {
        return transactionExecutor.recoveryStatus(normalizeScope(scope), normalizeIdempotencyKey(idempotencyKey),
                normalizeRequired(requestHash, "IDEMPOTENCY_REQUEST_HASH_REQUIRED"));
    }

    public enum RecoveryStatus { SUCCEEDED, FAILED, PROCESSING, UNKNOWN, NOT_FOUND, MISMATCH,
        CONFIRMED_SUCCEEDED, CONFIRMED_NOT_EXECUTED }

    /** Reads an owned receipt without reclaiming even an expired key. Callers must project only safe fields. */
    public <T> RecoveryResult<T> recoveryResult(String scope, String idempotencyKey, String requestHash,
                                               Class<T> responseType) {
        return transactionExecutor.recoveryResult(normalizeScope(scope), normalizeIdempotencyKey(idempotencyKey),
                normalizeRequired(requestHash, "IDEMPOTENCY_REQUEST_HASH_REQUIRED"), responseType);
    }

    public record RecoveryResult<T>(RecoveryStatus status, T response) {}

    /** Callers must bind both the scope to the actor and the retained response to the requested resource. */
    public ActiveReceipt retainedReceipt(String scope, String key) {
        return transactionExecutor.retainedReceipt(normalizeScope(scope), normalizeIdempotencyKey(key));
    }

    public record ActiveReceipt(long recordId, String requestHash) {}

    /** Locates an unexpired successful receipt without returning its response. */
    public ActiveReceipt activeReceipt(String scope, String idempotencyKey) {
        return transactionExecutor.activeReceipt(normalizeScope(scope), normalizeIdempotencyKey(idempotencyKey));
    }

    public record ActiveRequest(String requestHash, LocalDateTime updatedAt, boolean hasReceipt) {}

    public String retainedRequestHash(String scope, String idempotencyKey) {
        return transactionExecutor.retainedRequestHash(normalizeScope(scope), normalizeIdempotencyKey(idempotencyKey));
    }

    /** Probes an active key without claiming it. Command validation handles malformed keys. */
    public ActiveRequest activeRequest(String scope, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            return null;
        }
        return transactionExecutor.activeRequest(normalizeScope(scope), idempotencyKey.trim());
    }

    private <T> T execute(String scope, String idempotencyKey, String requestHash, Class<T> responseType,
                          Supplier<T> action, boolean retainSuccess) {
        return execute(scope, idempotencyKey, requestHash, responseType, action, retainSuccess, false);
    }

    private <T> T execute(String scope, String idempotencyKey, String requestHash, Class<T> responseType,
                          Supplier<T> action, boolean retainSuccess, boolean repeatableRead) {
        return execute(scope, idempotencyKey, requestHash, responseType, action, retainSuccess, repeatableRead,
                AdminCommandOrigin.current(scope));
    }

    private <T> T execute(String scope, String idempotencyKey, String requestHash, Class<T> responseType,
                          Supplier<T> action, boolean retainSuccess, boolean repeatableRead, AdminCommandOrigin origin) {
        return execute(scope, idempotencyKey, requestHash, responseType, action, retainSuccess, repeatableRead, origin, false);
    }

    private <T> T execute(String scope, String idempotencyKey, String requestHash, Class<T> responseType,
                          Supplier<T> action, boolean retainSuccess, boolean repeatableRead, AdminCommandOrigin origin,
                          boolean independentAction) {
        String normalizedScope = normalizeScope(scope);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String normalizedHash = normalizeRequired(requestHash, "IDEMPOTENCY_REQUEST_HASH_REQUIRED");

        LocalDateTime expiresAt = LocalDateTime.now(clock)
                .truncatedTo(ChronoUnit.SECONDS)
                .plus(DEFAULT_TTL);
        AdminIdempotencyTransactionExecutor.Claim<T> claim = transactionExecutor.claimBound(
                normalizedScope, normalizedKey, normalizedHash, expiresAt, responseType, retainSuccess, origin);
        if (!claim.executeAction()) {
            return claim.replayResponse();
        }

        boolean joinedTransaction = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
        AtomicReference<Object> businessRejection = new AtomicReference<>();
        if (joinedTransaction) {
            // The success marker joins the caller's transaction. Its later rollback
            // restores the independently committed claim to PROCESSING. Finalize only
            // after rollback releases its locks; REQUIRES_NEW before then deadlocks.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_ROLLED_BACK || independentAction) return;
                    try {
                        transactionExecutor.markFailed(claim.recordId(), "EnclosingTransactionRolledBack",
                                businessRejection.get());
                    } catch (RuntimeException failure) {
                        // An uncertain outcome stays fenced; never make it retryable.
                        log.error("Could not finalize rolled-back idempotency claim {}", claim.recordId(), failure);
                    }
                }
            });
        }
        try {
            return independentAction ? transactionExecutor.runClaimedIndependent(claim.recordId(), action)
                    : repeatableRead ? transactionExecutor.runClaimedRepeatableRead(claim.recordId(), action)
                    : transactionExecutor.runClaimed(claim.recordId(), action);
        } catch (RuntimeException ex) {
            Object businessResponse = ex instanceof AdminCommandBusinessRejection rejection
                    ? rejection.response() : null;
            if (businessResponse == null && ex instanceof BizException businessError) {
                if ("A2_COMMAND".equals(normalizedScope)) {
                    Object errorData = businessError.errorData();
                    if (errorData != null) {
                        // Replay only the exception's existing structured rejection payload.
                        businessResponse = ApiResult.fail(businessError.getCode(), businessError.getMessage(), errorData);
                    }
                } else if (eventFailureReceiptScope(normalizedScope)) {
                    int code = businessError.getCode();
                    String reason = businessError.getMessage();
                    if (code >= 400 && code < 500 && reason != null && reason.matches("[A-Z][A-Z0-9_]{0,127}")) {
                        // The original exception still rolls back the event action.
                        businessResponse = ApiResult.fail(code, reason);
                    }
                }
            }
            businessRejection.set(businessResponse);
            if (joinedTransaction || independentAction) throw ex;
            try {
                transactionExecutor.markFailed(claim.recordId(), errorSummary(ex), businessResponse);
            } catch (RuntimeException markFailedError) {
                // Preserve the business/SQL root cause; persistence of failure metadata must never mask it.
                ex.addSuppressed(markFailedError);
            }
            throw ex;
        }
    }

    static boolean eventFailureReceiptScope(String scope) {
        return scope.startsWith("APP:EVENT_JOIN:USER:")
                || scope.startsWith("APP:EVENT_CLAIM:USER:")
                || scope.startsWith("H4_EVENT:");
    }

    static boolean salaryClaimReceiptScope(String scope) {
        return scope.startsWith("APP:VRANK_SALARY:USER:");
    }

    private String errorSummary(RuntimeException ex) {
        // Exception text can contain request parameters, SQL fragments or an
        // upstream response. Durable idempotency metadata must be useful for
        // state recovery without becoming a second payload/audit store.
        return ex.getClass().getSimpleName();
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
