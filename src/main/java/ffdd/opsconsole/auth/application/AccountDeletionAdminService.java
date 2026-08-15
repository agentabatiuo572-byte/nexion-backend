package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.dto.AccountDeletionAdminView;
import ffdd.opsconsole.auth.dto.AdminAccountDeletionCommandRequest;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AccountDeletionAdminService {
    private final AppUserSecurityMapper mapper;
    private final AuthSessionMapper sessionMapper;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotency;

    @Transactional(readOnly = true)
    public AccountDeletionAdminView find(String requestNo) {
        if (!StringUtils.hasText(requestNo)) throw new BizException(422, "ACCOUNT_DELETION_REQUEST_NO_REQUIRED");
        String normalized = normalizeRequestNo(requestNo);
        Map<String, Object> row = mapper.accountDeletionByRequestNoForUpdate(normalized);
        if (row == null) throw new BizException(404, "ACCOUNT_DELETION_REQUEST_NOT_FOUND");
        return view(row, false, false);
    }

    @Transactional(readOnly = true)
    public List<AccountDeletionAdminView> list(String status, int page, int limit) {
        int safePage = Math.min(100_000, Math.max(1, page));
        int safeLimit = Math.min(100, Math.max(1, limit));
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (normalized != null && !isStatus(normalized)) throw new BizException(422, "ACCOUNT_DELETION_STATUS_INVALID");
        return mapper.listAccountDeletions(normalized, (safePage - 1) * safeLimit, safeLimit).stream()
                .map(row -> view(row, false, false)).toList();
    }

    public AccountDeletionAdminView review(String requestNo, String key, AdminAccountDeletionCommandRequest request) {
        return command("REVIEW", requestNo, key, request, () -> transition(requestNo, "IN_REVIEW", request));
    }

    public AccountDeletionAdminView block(String requestNo, String key, AdminAccountDeletionCommandRequest request) {
        return command("BLOCK", requestNo, key, request, () -> transition(requestNo, "BLOCKED", request));
    }

    public AccountDeletionAdminView complete(String requestNo, String key, AdminAccountDeletionCommandRequest request) {
        return command("COMPLETE", requestNo, key, request, () -> completeNow(requestNo, request));
    }

    public AccountDeletionAdminView cancel(String requestNo, String key, AdminAccountDeletionCommandRequest request) {
        return command("CANCEL", requestNo, key, request, () -> transition(requestNo, "CANCELLED", request));
    }

    private AccountDeletionAdminView command(
            String operation, String requestNo, String key, AdminAccountDeletionCommandRequest request,
            java.util.function.Supplier<AccountDeletionAdminView> action) {
        validateCommand(requestNo, key, request);
        return idempotency.execute(
                "USER_ACCOUNT_DELETION:" + operation + ":" + requestNo.trim(), key.trim(),
                hash(requestNo, request), AccountDeletionAdminView.class, action);
    }

    @Transactional
    AccountDeletionAdminView transition(String requestNo, String target, AdminAccountDeletionCommandRequest request) {
        Map<String, Object> row = locked(requestNo);
        String from = text(row, "status");
        AccountDeletionStateMachine.requireTransition(from, target, request.reason().trim());
        long version = number(row, "version");
        requireExpected(version, request.expectedVersion());
        if (mapper.transitionAccountDeletion(requestNo.trim(), from, target, version, request.reason().trim(), null) != 1) {
            throw new BizException(409, "ACCOUNT_DELETION_CONCURRENT_UPDATE");
        }
        Map<String, Object> updated = locked(requestNo);
        audit("ADMIN_ACCOUNT_DELETION_" + target, updated, request.reason().trim());
        return view(updated, false, false);
    }

    @Transactional
    AccountDeletionAdminView completeNow(String requestNo, AdminAccountDeletionCommandRequest request) {
        Map<String, Object> row = locked(requestNo);
        String from = text(row, "status");
        AccountDeletionStateMachine.requireTransition(from, "COMPLETED", request.reason().trim());
        long version = number(row, "version");
        requireExpected(version, request.expectedVersion());
        Long userId = numberObject(row, "userId");
        boolean unsettled;
        try {
            unsettled = mapper.hasUnsettledFundsOrOrders(userId) > 0;
        } catch (RuntimeException ex) {
            // A missing/failed financial read must never be interpreted as an empty ledger.
            String reason = "UNSETTLED_STATE_UNAVAILABLE";
            if (mapper.transitionAccountDeletion(requestNo.trim(), from, "BLOCKED", version, reason, null) != 1) {
                throw new BizException(409, "ACCOUNT_DELETION_CONCURRENT_UPDATE");
            }
            Map<String, Object> blocked = locked(requestNo);
            audit("ADMIN_ACCOUNT_DELETION_BLOCKED", blocked, reason);
            return view(blocked, false, false);
        }
        if (unsettled) {
            String reason = "UNSETTLED_FUNDS_OR_ORDERS";
            if (mapper.transitionAccountDeletion(requestNo.trim(), from, "BLOCKED", version, reason, null) != 1) {
                throw new BizException(409, "ACCOUNT_DELETION_CONCURRENT_UPDATE");
            }
            Map<String, Object> blocked = locked(requestNo);
            audit("ADMIN_ACCOUNT_DELETION_BLOCKED", blocked, reason);
            return view(blocked, false, false);
        }
        int disabled = mapper.disableAndAnonymizeUser(userId);
        mapper.anonymizeUserProfile(userId);
        int revoked = sessionMapper.revokeAllUserSessions(userId);
        if (mapper.transitionAccountDeletion(requestNo.trim(), from, "COMPLETED", version,
                request.reason().trim(), null) != 1) {
            throw new BizException(409, "ACCOUNT_DELETION_CONCURRENT_UPDATE");
        }
        Map<String, Object> completed = locked(requestNo);
        audit("ADMIN_ACCOUNT_DELETION_COMPLETED", completed, request.reason().trim());
        return view(completed, revoked > 0, disabled > 0);
    }

    private Map<String, Object> locked(String requestNo) {
        Map<String, Object> row = mapper.accountDeletionByRequestNoForUpdate(requestNo.trim());
        if (row == null) throw new BizException(404, "ACCOUNT_DELETION_REQUEST_NOT_FOUND");
        return row;
    }

    private void validateCommand(String requestNo, String key, AdminAccountDeletionCommandRequest request) {
        if (!StringUtils.hasText(requestNo)) throw new BizException(422, "ACCOUNT_DELETION_REQUEST_NO_REQUIRED");
        normalizeRequestNo(requestNo);
        if (!StringUtils.hasText(key) || key.trim().length() > 128) throw new BizException(422, "IDEMPOTENCY_KEY_INVALID");
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new BizException(422, "ACCOUNT_DELETION_VERSION_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() > 255) {
            throw new BizException(422, "ACCOUNT_DELETION_REASON_REQUIRED");
        }
    }

    private String normalizeRequestNo(String requestNo) {
        String normalized = requestNo.trim();
        if (!normalized.matches("ADR-[a-fA-F0-9]{32}")) {
            throw new BizException(422, "ACCOUNT_DELETION_REQUEST_NO_INVALID");
        }
        return normalized;
    }

    private boolean isStatus(String status) {
        return switch (status) {
            case "REQUESTED", "IN_REVIEW", "BLOCKED", "COMPLETED", "CANCELLED" -> true;
            default -> false;
        };
    }

    private void requireExpected(long actual, Long expected) {
        if (actual != expected) throw new BizException(409, "ACCOUNT_DELETION_VERSION_CONFLICT");
    }

    private String hash(String requestNo, AdminAccountDeletionCommandRequest request) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((requestNo.trim() + "|" + request.expectedVersion() + "|" + request.reason().trim())
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("ACCOUNT_DELETION_HASH_UNAVAILABLE", ex);
        }
    }

    private AccountDeletionAdminView view(Map<String, Object> row, boolean sessionsRevoked, boolean accountDisabled) {
        return new AccountDeletionAdminView(text(row, "requestNo"), numberObject(row, "userId"), text(row, "status"),
                number(row, "version"), date(row, "requestedAt"), date(row, "reviewedAt"), date(row, "completedAt"),
                textNullable(row, "reason"), textNullable(row, "blockReason"), sessionsRevoked, accountDisabled);
    }

    private void audit(String action, Map<String, Object> row, String reason) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType("USER_ACCOUNT_DELETION").resourceId(text(row, "requestNo"))
                .userId(numberObject(row, "userId")).actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("system")).result("SUCCESS").riskLevel("HIGH")
                .detail(Map.of("status", text(row, "status"), "version", number(row, "version"), "reason", reason))
                .build());
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) throw new IllegalStateException("ACCOUNT_DELETION_FIELD_MISSING:" + key);
        return String.valueOf(value);
    }

    private String textNullable(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private Long numberObject(Map<String, Object> row, String key) {
        return number(row, key);
    }

    private LocalDateTime date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }
}
