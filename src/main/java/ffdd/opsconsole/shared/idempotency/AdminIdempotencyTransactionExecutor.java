package ffdd.opsconsole.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.time.LocalDateTime;
import java.util.Set;
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
    private final AdminIdempotencyRollbackEvidence rollbackEvidence;

    /** Never claims, resets, deserializes a response, or executes a business action. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public AdminIdempotencyService.RecoveryStatus recoveryStatus(String scope, String idempotencyKey, String requestHash) {
        AdminIdempotencyRecordEntity record = recordMapper.selectCurrent(scope, idempotencyKey);
        return recoveryStatus(record, requestHash);
    }

    private AdminIdempotencyService.RecoveryStatus recoveryStatus(AdminIdempotencyRecordEntity record, String requestHash) {
        if (record == null || !Integer.valueOf(0).equals(record.getIsDeleted())) {
            return AdminIdempotencyService.RecoveryStatus.NOT_FOUND;
        }
        if (!requestHash.equals(record.getRequestHash())) return AdminIdempotencyService.RecoveryStatus.MISMATCH;
        return switch (String.valueOf(record.getStatus())) {
            case STATUS_SUCCEEDED -> StringUtils.hasText(record.getResponseJson())
                    ? AdminIdempotencyService.RecoveryStatus.SUCCEEDED : AdminIdempotencyService.RecoveryStatus.UNKNOWN;
            case STATUS_FAILED -> AdminIdempotencyService.RecoveryStatus.FAILED;
            case STATUS_PROCESSING -> AdminIdempotencyService.RecoveryStatus.PROCESSING;
            case "CONFIRMED_SUCCEEDED" -> StringUtils.hasText(record.getResponseJson())
                    ? AdminIdempotencyService.RecoveryStatus.SUCCEEDED : AdminIdempotencyService.RecoveryStatus.CONFIRMED_SUCCEEDED;
            case "CONFIRMED_NOT_EXECUTED" -> AdminIdempotencyService.RecoveryStatus.CONFIRMED_NOT_EXECUTED;
            default -> AdminIdempotencyService.RecoveryStatus.UNKNOWN;
        };
    }

    /** A committed read only: never locks, claims, resets, or transitions an expired receipt. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public <T> AdminIdempotencyService.RecoveryResult<T> recoveryResult(
            String scope, String idempotencyKey, String requestHash, Class<T> responseType) {
        AdminIdempotencyRecordEntity record = recordMapper.selectCurrent(scope, idempotencyKey);
        var status = recoveryStatus(record, requestHash);
        boolean failed = status == AdminIdempotencyService.RecoveryStatus.FAILED;
        boolean hasRecordedFailure = failed && (AdminIdempotencyService.eventFailureReceiptScope(scope)
                || AdminIdempotencyService.salaryClaimReceiptScope(scope))
                && StringUtils.hasText(record.getResponseJson());
        if (status != AdminIdempotencyService.RecoveryStatus.SUCCEEDED && !hasRecordedFailure) {
            return new AdminIdempotencyService.RecoveryResult<>(status, null);
        }
        var missingResponseStatus = failed ? status : AdminIdempotencyService.RecoveryStatus.UNKNOWN;
        try {
            T response = objectMapper.readValue(record.getResponseJson(), responseType);
            return new AdminIdempotencyService.RecoveryResult<>(response == null ? missingResponseStatus : status, response);
        } catch (JsonProcessingException ex) {
            return new AdminIdempotencyService.RecoveryResult<>(missingResponseStatus, null);
        }
    }

    /** A committed, unexpired success receipt locator; never claims or executes. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public AdminIdempotencyService.ActiveReceipt activeReceipt(String scope, String idempotencyKey) {
        AdminIdempotencyRecordEntity record = recordMapper.selectActive(scope, idempotencyKey);
        if (record == null || !STATUS_SUCCEEDED.equals(record.getStatus())
                || !StringUtils.hasText(record.getResponseJson())) return null;
        return new AdminIdempotencyService.ActiveReceipt(record.getId(), record.getRequestHash());
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public AdminIdempotencyService.ActiveReceipt retainedReceipt(String scope, String idempotencyKey) {
        var record = recordMapper.selectCurrent(scope, idempotencyKey);
        if (record == null || recoveryStatus(record, record.getRequestHash()) != AdminIdempotencyService.RecoveryStatus.SUCCEEDED) {
            return null;
        }
        return new AdminIdempotencyService.ActiveReceipt(record.getId(), record.getRequestHash());
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public AdminIdempotencyService.ActiveRequest activeRequest(String scope, String idempotencyKey) {
        var record = recordMapper.selectActive(scope, idempotencyKey);
        if (record == null) return null;
        return new AdminIdempotencyService.ActiveRequest(record.getRequestHash(), record.getUpdatedAt(),
                recoveryStatus(record, record.getRequestHash()) == AdminIdempotencyService.RecoveryStatus.SUCCEEDED);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public String retainedRequestHash(String scope, String idempotencyKey) {
        var record = recordMapper.selectCurrent(scope, idempotencyKey);
        return record == null || !Integer.valueOf(0).equals(record.getIsDeleted()) ? null : record.getRequestHash();
    }

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

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public <T> Claim<T> claimBound(String scope, String key, String requestHash, LocalDateTime expiresAt,
                                  Class<T> responseType, boolean retainSuccess, AdminCommandOrigin origin) {
        return claimInternal(scope, key, requestHash, expiresAt, responseType, retainSuccess, origin);
    }

    private <T> Claim<T> claimInternal(String scope, String idempotencyKey, String requestHash,
                                      LocalDateTime expiresAt, Class<T> responseType, boolean retainSuccess) {
        AdminCommandOrigin origin = AdminCommandOrigin.current(scope);
        return claimInternal(scope, idempotencyKey, requestHash, expiresAt, responseType, retainSuccess, origin);
    }

    private <T> Claim<T> claimInternal(String scope, String idempotencyKey, String requestHash,
                                      LocalDateTime expiresAt, Class<T> responseType, boolean retainSuccess,
                                      AdminCommandOrigin origin) {
        boolean scopeUsesOriginRetention = originAddsRetention(scope);
        boolean originRetains = origin != null && scopeUsesOriginRetention;
        retainSuccess = retainSuccess || originRetains;
        AdminIdempotencyRecordEntity record = null;
        AdminIdempotencyRecordEntity existing = recordMapper.selectActive(scope, idempotencyKey);
        if (existing != null) {
            return resolveKnownRecord(existing, requestHash, expiresAt, responseType, origin);
        }

        AdminIdempotencyRecordEntity current = record == null
                ? recordMapper.selectCurrent(scope, idempotencyKey)
                : null;
        if (current != null && current.getStatus().startsWith("CONFIRMED_")) {
            return resolveKnownRecord(current, requestHash, expiresAt, responseType, origin);
        } else if (current != null && (retainSuccess
                || scopeUsesOriginRetention && current.getOriginAdminId() != null)
                && (STATUS_SUCCEEDED.equals(current.getStatus()) || STATUS_FAILED.equals(current.getStatus()))) {
            // Never recycle a retained success; FAILED retries use a status CAS,
            // so a concurrent completion cannot be overwritten by an expiry reset.
            return resolveKnownRecord(current, requestHash, expiresAt, responseType, origin);
        } else if (record == null && current != null && STATUS_UNKNOWN.equals(current.getStatus())) {
            throw conflict("IDEMPOTENCY_RESULT_UNKNOWN");
        } else if (record == null && current != null && STATUS_PROCESSING.equals(current.getStatus())
                && Integer.valueOf(0).equals(current.getIsDeleted())) {
            // Database NOW() is the sole lease clock. The independent CAS is
            // deliberately attempted even when this JVM has a different zone.
            int transitioned = expiryTransitionExecutor.markCurrentExpiredProcessingUnknown(
                    current.getId(), scope, idempotencyKey);
            if (transitioned == 1 && !AdminIdempotencyService.salaryClaimReceiptScope(scope)) {
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
            return resolveKnownRecord(refreshed, requestHash, expiresAt, responseType, origin);
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
            String targetsJson = origin == null || origin.targets().isEmpty() ? null : writeJson(origin.targets());
            if (targetsJson != null && recordMapper.countUnresolvedTargets(targetsJson, scope, idempotencyKey,
                    permittedBatchParent(scope, idempotencyKey, targetsJson)) > 0) {
                throw conflict("IDEMPOTENCY_ORIGINAL_OPERATION_REQUIRES_INVESTIGATION");
            }
            if (origin != null && origin.target() != null && recordMapper.countUnresolvedOrigin(
                    scope, origin.method(), origin.path(), origin.target(), idempotencyKey) > 0) {
                throw conflict("IDEMPOTENCY_ORIGINAL_OPERATION_REQUIRES_INVESTIGATION");
            }
            record = new AdminIdempotencyRecordEntity();
            record.setScope(scope);
            record.setIdempotencyKey(idempotencyKey);
            record.setRequestHash(requestHash);
            record.setStatus(STATUS_PROCESSING);
            record.setExpiresAt(expiresAt);
            record.setIsDeleted(0);
            if (origin != null) {
                record.setOriginAdminId(origin.actorId());
                record.setOriginMethod(origin.method());
                record.setOriginPath(origin.path());
                record.setOriginTarget(origin.target());
                record.setOriginTargets(targetsJson);
                record.setOriginReadAuthority(origin.readAuthority());
            }
            record.setRollbackConfirmed(0);
            try {
                recordMapper.insert(record);
            } catch (DuplicateKeyException ex) {
                // Leave the claim's REPEATABLE_READ snapshot without locking the winner.
                // Its business transaction may be waiting for a nested claim to commit;
                // waiting here can hold gap locks that block that nested INSERT.
                AdminIdempotencyRecordEntity duplicate =
                        expiryTransitionExecutor.loadActiveCommitted(scope, idempotencyKey);
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
        return completeClaim(recordId, action);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public <T> T runClaimedIndependent(Long recordId, Supplier<T> action) {
        return completeClaim(recordId, action, false);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 10, rollbackFor = Exception.class)
    public <T> T runClaimedRepeatableRead(Long recordId, Supplier<T> action) {
        return completeClaim(recordId, action);
    }

    private <T> T completeClaim(Long recordId, Supplier<T> action) {
        return completeClaim(recordId, action, true);
    }

    private <T> T completeClaim(Long recordId, Supplier<T> action, boolean rollbackSafe) {
        if (rollbackSafe) org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) rollbackEvidence.record(recordId);
                    }
                });
        var original = recordMapper.selectCurrentByIdForUpdate(recordId);
        if (original == null || !STATUS_PROCESSING.equals(original.getStatus())) throw conflict("IDEMPOTENCY_CLAIM_STATE_LOST");
        var previous = AdminIdempotencyExecutionContext.enter(original);
        try {
            T result = action.get();
            if (recordMapper.markSucceeded(recordId, writeJson(result)) != 1) {
                throw conflict("IDEMPOTENCY_SUCCESS_STATE_LOST");
            }
            return result;
        } finally {
            AdminIdempotencyExecutionContext.restore(previous);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(Long recordId, String errorMessage) {
        markFailed(recordId, errorMessage, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(Long recordId, String errorMessage, Object response) {
        int updated = response == null
                ? recordMapper.markFailed(recordId, errorMessage)
                : recordMapper.markFailedWithResponse(recordId, errorMessage, writeJson(response));
        if (updated != 1) {
            throw conflict("IDEMPOTENCY_FAILURE_STATE_LOST");
        }
    }

    private <T> Claim<T> resolveKnownRecord(
            AdminIdempotencyRecordEntity record,
            String requestHash,
            LocalDateTime expiresAt,
            Class<T> responseType,
            AdminCommandOrigin origin) {
        if (AdminCommandOrigin.g2ParameterKey(record.getScope()) != null && record.getOriginAdminId() != null) {
            if (origin == null || !Long.valueOf(origin.actorId()).equals(record.getOriginAdminId())) {
                throw conflict("IDEMPOTENCY_RESULT_UNKNOWN");
            }
            if (STATUS_FAILED.equals(record.getStatus()) && StringUtils.hasText(record.getResponseJson())) {
                if (!requestHash.equals(record.getRequestHash())) throw conflict("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                // A captured business rejection already rolled back and must not execute again under the same key.
                return Claim.replayed(readJson(record.getResponseJson(), responseType));
            }
        }
        if ((conversationSendScope(record.getScope()) || AdminIdempotencyService.salaryClaimReceiptScope(record.getScope()))
                && STATUS_FAILED.equals(record.getStatus())
                && StringUtils.hasText(record.getResponseJson())) {
            if (!requestHash.equals(record.getRequestHash())) throw conflict("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
            // Both operations retain the original rejection after the business transaction rolled back.
            return Claim.replayed(readJson(record.getResponseJson(), responseType));
        }
        if ("A2_COMMAND".equals(record.getScope()) && STATUS_FAILED.equals(record.getStatus())) {
            if (!requestHash.equals(record.getRequestHash())) throw conflict("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
            if (a2TicketCommand(record)) {
                if (!StringUtils.hasText(record.getResponseJson())) {
                    // A ticket command without a trusted rejection cannot prove that retrying is safe.
                    throw conflict("IDEMPOTENCY_PREVIOUS_REQUEST_FAILED");
                }
                // A2 proposals and decisions replay their captured business rejection instead of
                // re-executing. The trusted origin action/ticket must still identify the same command.
                replayA2Rejection(record, origin, record.getResponseJson());
            } else if (StringUtils.hasText(record.getResponseJson())) {
                // Non-ticket A2 direct writes keep their original explicit-rejection replay;
                // without a response the legacy failed-retry path remains available below.
                replayA2Rejection(record, null, record.getResponseJson());
            }
        }
        if (!STATUS_FAILED.equals(record.getStatus())) {
            return Claim.replayed(replay(record, requestHash, responseType));
        }
        if (!requestHash.equals(record.getRequestHash())) {
            return Claim.replayed(replay(record, requestHash, responseType));
        }
        if (origin != null && !origin.targets().isEmpty()) {
            String currentTargets = writeJson(origin.targets());
            if (recordMapper.countUnresolvedTargets(currentTargets, record.getScope(), record.getIdempotencyKey(),
                    permittedBatchParent(record.getScope(), record.getIdempotencyKey(), currentTargets)) > 0)
                throw conflict("IDEMPOTENCY_ORIGINAL_OPERATION_REQUIRES_INVESTIGATION");
        }
        if (record.getOriginTarget() != null && recordMapper.countUnresolvedOrigin(record.getScope(),
                record.getOriginMethod(), record.getOriginPath(), record.getOriginTarget(), record.getIdempotencyKey()) > 0) {
            throw conflict("IDEMPOTENCY_ORIGINAL_OPERATION_REQUIRES_INVESTIGATION");
        }
        if (record.getOriginTargets() != null && recordMapper.countUnresolvedTargets(record.getOriginTargets(),
                record.getScope(), record.getIdempotencyKey(), permittedBatchParent(
                        record.getScope(), record.getIdempotencyKey(), record.getOriginTargets())) > 0) {
            throw conflict("IDEMPOTENCY_ORIGINAL_OPERATION_REQUIRES_INVESTIGATION");
        }
        if (recordMapper.resetFailedById(record.getId(), requestHash, expiresAt) == 1) {
            return Claim.claimed(record.getId());
        }
        // A lost retry CAS must reload outside the claim's REPEATABLE_READ snapshot.
        AdminIdempotencyRecordEntity raced = expiryTransitionExecutor.loadActiveCommitted(
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
        if ((STATUS_SUCCEEDED.equals(record.getStatus()) || "CONFIRMED_SUCCEEDED".equals(record.getStatus()))
                && StringUtils.hasText(record.getResponseJson())) {
            return readJson(record.getResponseJson(), responseType);
        }
        if (STATUS_FAILED.equals(record.getStatus())) {
            throw conflict("IDEMPOTENCY_PREVIOUS_REQUEST_FAILED");
        }
        if (STATUS_UNKNOWN.equals(record.getStatus())) {
            throw conflict("IDEMPOTENCY_RESULT_UNKNOWN");
        }
        if (record.getStatus().startsWith("CONFIRMED_")) {
            throw conflict("IDEMPOTENCY_INVESTIGATION_" + record.getStatus());
        }
        throw conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS");
    }

    /** Only the existing D2 batch-to-selected-review child relation may share its parent's PROCESSING fence. */
    private java.util.List<Long> permittedBatchParent(String scope, String key, String targetsJson) {
        var parent = AdminIdempotencyExecutionContext.current();
        if (parent == null || !java.util.Set.of("D2_BATCH_APPROVE", "D2_BATCH_REJECT", "D2_BATCH_DELAY", "D2_BATCH_FREEZE").contains(parent.scope())
                || !scope.startsWith("D2_REVIEW_") || targetsJson == null || parent.targetsJson() == null)
            return java.util.List.of(-1L);
        try {
            var child = objectMapper.readTree(targetsJson);
            if (!child.isArray() || child.size() != 1 || !"REVIEW".equals(child.get(0).path("action").asText())
                    || !"D2".equals(child.get(0).path("domain").asText()) || !"withdrawal".equals(child.get(0).path("type").asText()))
                return java.util.List.of(-1L);
            String childId = child.get(0).path("id").asText();
            if (!scope.equals("D2_REVIEW_" + childId.toUpperCase(java.util.Locale.ROOT))) return java.util.List.of(-1L);
            String expectedKey = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    ("D2_ITEM|" + parent.commandKey() + "|" + childId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!expectedKey.equals(key)) return java.util.List.of(-1L);
            for (var target : objectMapper.readTree(parent.targetsJson())) {
                if (target.equals(child.get(0))) return java.util.List.of(parent.recordId());
            }
            return java.util.List.of(-1L);
        } catch (JsonProcessingException invalid) {
            return java.util.List.of(-1L);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", unavailable);
        }
    }

    /** A1 permission and A7 menu keep their original execute(false) 24h TTL; origin is only provenance. */
    private static boolean originAddsRetention(String scope) {
        return !("A1_PERMISSION_REGISTER".equals(scope) || scope.startsWith("A7_MENU_"));
    }

    private static boolean conversationSendScope(String scope) {
        return scope != null && (scope.startsWith("M3_CONVERSATION_REPLY:")
                || scope.startsWith("M3_CONVERSATION_INITIATE:")
                || scope.startsWith("APP_CONVERSATION_REPLY:"));
    }


    /** Replays the captured A2 business rejection, including any structured result. */
    private void replayA2Rejection(AdminIdempotencyRecordEntity record, AdminCommandOrigin origin, String responseJson) {
        Integer code = null;
        String message = null;
        ApiResult<?> response = null;
        try {
            var value = objectMapper.readTree(responseJson);
            if (value != null && value.has("code") && value.get("code").canConvertToInt()) code = value.get("code").asInt();
            if (value != null && value.has("message") && value.get("message").isTextual()) message = value.get("message").asText();
            if (value != null && value.isObject()) response = objectMapper.treeToValue(value, ApiResult.class);
        } catch (JsonProcessingException invalid) {
            // No trusted code/message to surface; fall back to a generic failed-previous-request conflict.
        }
        if (code == null || code == 0) {
            throw conflict("IDEMPOTENCY_PREVIOUS_REQUEST_FAILED");
        }
        if (origin != null && !a2RejectionIdentity(record, origin)) {
            throw conflict("IDEMPOTENCY_PREVIOUS_REQUEST_FAILED");
        }
        throw new AdminCommandBusinessRejection(code, message == null ? "A2_COMMAND_REJECTED" : message, response);
    }

    /** Ticket commands are proposals/decisions; other A2_COMMAND writers are direct parameter commands. */
    private boolean a2TicketCommand(AdminIdempotencyRecordEntity record) {
        if (!StringUtils.hasText(record.getOriginTargets())) return false;
        try {
            for (var target : objectMapper.readTree(record.getOriginTargets())) {
                String action = target.path("action").asText("");
                if (action.startsWith("A2_PROPOSE")
                        || Set.of("A2_APPROVE", "A2_REJECT", "A2_WITHDRAW").contains(action)) return true;
            }
        } catch (JsonProcessingException invalid) {
            return false;
        }
        return false;
    }

    /** The persisted A2 origin action (proposal or exact decision ticket) must identify this request. */
    private boolean a2RejectionIdentity(AdminIdempotencyRecordEntity record, AdminCommandOrigin origin) {
        if (!StringUtils.hasText(record.getOriginTargets())) return false;
        try {
            for (var target : objectMapper.readTree(record.getOriginTargets())) {
                String action = target.path("action").asText("");
                if (action.startsWith("A2_PROPOSE")) return true;
                if (Set.of("A2_APPROVE", "A2_REJECT", "A2_WITHDRAW").contains(action)) {
                    String ticketId = target.path("id").asText("");
                    if (!StringUtils.hasText(ticketId)) return false;
                    return origin.targets().stream()
                            .anyMatch(current -> action.equals(current.action()) && ticketId.equals(current.id()));
                }
            }
        } catch (JsonProcessingException invalid) {
            return false;
        }
        return false;
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
            return objectMapper.readerFor(responseType)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(value);
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
