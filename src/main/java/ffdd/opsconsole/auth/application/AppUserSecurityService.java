package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.dto.AppPasswordChangeRequest;
import ffdd.opsconsole.auth.dto.AppAccountDeletionRequest;
import ffdd.opsconsole.auth.dto.AppAccountDeletionCancelRequest;
import ffdd.opsconsole.auth.dto.AppSecurityMutationResponse;
import ffdd.opsconsole.auth.dto.AppSecurityStateResponse;
import ffdd.opsconsole.auth.dto.AppTwoFactorUpdateRequest;
import ffdd.opsconsole.auth.dto.AppTwoFactorChallengeRequest;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SupportedUserPhonePolicy;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppUserSecurityService {
    private static final int SESSION_IDLE_DAYS = 30;
    private static final int MAX_PASSWORD_LENGTH = 64;
    private static final String SESSION_ID_PATTERN = "[A-Za-z0-9_-]{1,128}";
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "12345678", "123456789", "password", "password1", "passw0rd",
            "qwerty123", "admin123", "welcome1", "letmein1");
    private static final int TWO_FACTOR_OTP_TTL_MINUTES = 10;

    private final AppUserSecurityMapper securityMapper;
    private final AuthSessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final UserOtpDeliveryService otpDeliveryService;
    private final AppUserSecurityVerificationGuard verificationGuard;
    private final UserOpsMapper userMapper;

    @Transactional(readOnly = true)
    public AppSecurityStateResponse overview(Long userId, String currentSessionId) {
        return overview(userId, currentSessionId, null);
    }

    @Transactional(readOnly = true)
    public AppSecurityStateResponse overview(Long userId, String currentSessionId, String cursor) {
        requireContext(userId, currentSessionId);
        Long beforeId = null;
        if (cursor != null) {
            try {
                if (!cursor.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
                beforeId = Long.valueOf(cursor);
            } catch (NumberFormatException ex) { throw new BizException(422, "SECURITY_CURSOR_INVALID"); }
        }
        int idleDays = effectiveSessionIdleDays();
        var rows = sessionMapper.pageOtherUserSessions(userId, currentSessionId, idleDays, beforeId);
        List<AppSecurityStateResponse.Session> sessions = new java.util.ArrayList<>();
        if (beforeId == null) {
            var current = sessionMapper.currentUserSession(userId, currentSessionId, idleDays);
            if (current != null) sessions.add(toResponse(current, currentSessionId));
        }
        rows.stream().limit(20).map(row -> toResponse(row, currentSessionId)).forEach(sessions::add);
        String nextCursor = rows.size() > 20 ? String.valueOf(rows.get(19).getId()) : null;
        return new AppSecurityStateResponse(
                securityMapper.twoFactorEnabled(userId),
                securityMapper.passwordChangedAt(userId),
                sessions, nextCursor);
    }

    private int effectiveSessionIdleDays() {
        try {
            String configured = securityMapper.sessionIdleDaysConfig();
            int value = Integer.parseInt(configured == null ? null : configured.trim());
            return value >= 7 && value <= 90 ? value : SESSION_IDLE_DAYS;
        } catch (NumberFormatException ex) {
            return SESSION_IDLE_DAYS;
        }
    }

    @Transactional(noRollbackFor = PreWriteRejection.class)
    public AppSecurityMutationResponse changePassword(
            Long userId, String currentSessionId, String commandKey, AppPasswordChangeRequest request) {
        requireContext(userId, currentSessionId);
        if (commandKey == null || !commandKey.matches("[A-Za-z0-9:_-]{8,128}"))
            throw new BizException(422, "PASSWORD_COMMAND_KEY_INVALID");
        // The user row serializes simultaneous attempts; the receipt commits atomically with the change.
        String hash = securityMapper.passwordHashForUpdate(userId);
        if (!StringUtils.hasText(hash)) throw new BizException(401, "USER_AUTH_REQUIRED");
        var receipt = securityMapper.passwordChangeReceipt(userId, currentSessionId, commandKey);
        if (receipt != null) {
            if (request == null || !safeMatches(request.newPassword(), hash))
                throw new BizException(409, "PASSWORD_COMMAND_INPUT_CHANGED");
            return receipt;
        }
        var result = changePassword(userId, currentSessionId, request);
        if (securityMapper.insertPasswordChangeReceipt(userId, currentSessionId, commandKey,
                result.passwordChangedAt(), result.revokedSessionCount()) != 1)
            throw new IllegalStateException("PASSWORD_COMMAND_RECEIPT_FAILED");
        return result;
    }

    public AppSecurityMutationResponse passwordCommandReceipt(Long userId, String sessionId, String commandKey) {
        requireContext(userId, sessionId);
        if (commandKey == null || !commandKey.matches("[A-Za-z0-9:_-]{8,128}"))
            throw new BizException(422, "PASSWORD_COMMAND_KEY_INVALID");
        return securityMapper.passwordChangeReceipt(userId, sessionId, commandKey);
    }

    @Transactional
    public AppSecurityMutationResponse revokeSession(Long userId, String currentSessionId, String targetSessionId) {
        requireContext(userId, currentSessionId);
        if (!StringUtils.hasText(targetSessionId)) {
            throw new BizException(422, "SESSION_ID_REQUIRED");
        }
        String normalizedTarget = targetSessionId.trim();
        if (!normalizedTarget.matches(SESSION_ID_PATTERN)) {
            throw new BizException(422, "SESSION_ID_INVALID");
        }
        if (currentSessionId.equals(normalizedTarget)) {
            throw new BizException(409, "CURRENT_SESSION_REVOKE_FORBIDDEN");
        }
        int revoked = sessionMapper.revokeOwnedUserSession(userId, normalizedTarget);
        if (revoked != 1) {
            throw new BizException(404, "SESSION_NOT_ACTIVE_OR_NOT_OWNED");
        }
        recordRequired(userId, "USER_SESSION_REVOKED", "USER_SESSION", shortId(normalizedTarget),
                Map.of("scope", "ONE_OTHER_SESSION", "revokedSessionCount", revoked));
        return AppSecurityMutationResponse.sessions(revoked);
    }

    @Transactional
    public AppSecurityMutationResponse revokeOtherSessions(Long userId, String currentSessionId) {
        requireContext(userId, currentSessionId);
        int revoked = sessionMapper.revokeOtherUserSessions(userId, currentSessionId);
        recordRequired(userId, "USER_OTHER_SESSIONS_REVOKED", "USER_SESSION", String.valueOf(userId),
                Map.of("scope", "ALL_OTHER_SESSIONS", "revokedSessionCount", revoked));
        return AppSecurityMutationResponse.sessions(revoked);
    }

    @Transactional(noRollbackFor = PreWriteRejection.class)
    public AppSecurityMutationResponse changePassword(
            Long userId, String currentSessionId, AppPasswordChangeRequest request) {
        requireContext(userId, currentSessionId);
        if (request == null || !strongPassword(request.newPassword())) {
            throw new BizException(422, "USER_NEW_PASSWORD_POLICY_REJECTED");
        }
        String passwordHash = verifyCurrentPassword(userId, request.currentPassword(), "PASSWORD_CHANGE");
        if (safeMatches(request.newPassword(), passwordHash)) {
            throw new PreWriteRejection(422, "USER_NEW_PASSWORD_MUST_DIFFER");
        }
        if (securityMapper.updatePasswordHash(userId, passwordEncoder.encode(request.newPassword())) != 1
                || securityMapper.markPasswordChanged(userId) < 1) {
            throw new IllegalStateException("USER_PASSWORD_STATE_CHANGED");
        }
        int revoked = sessionMapper.revokeOtherUserSessions(userId, currentSessionId);
        LocalDateTime changedAt = securityMapper.passwordChangedAt(userId);
        recordRequired(userId, "USER_PASSWORD_CHANGED", "USER_SECURITY", String.valueOf(userId),
                Map.of("revokedOtherSessionCount", revoked));
        return AppSecurityMutationResponse.password(changedAt == null ? LocalDateTime.now() : changedAt, revoked);
    }

    @Transactional(noRollbackFor = PreWriteRejection.class)
    public Map<String, Object> sendTwoFactorChallenge(Long userId, AppTwoFactorChallengeRequest request) {
        if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        if (request == null || request.enabled() == null || !StringUtils.hasText(request.currentPassword())) {
            throw new BizException(422, "CURRENT_PASSWORD_REQUIRED");
        }
        verifyCurrentPassword(userId, request.currentPassword(), "TWO_FACTOR_CHALLENGE");
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !SupportedUserPhonePolicy.isSupportedDestination(
                user.getCountryCode(), user.getPhone())) {
            throw new PreWriteRejection(422, "USER_PHONE_INVALID");
        }
        if (!otpDeliveryService.available(user.getCountryCode())) {
            throw new PreWriteRejection(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        String direction = request.enabled() ? "E" : "D";
        String challengeNo = "SEC2FA-" + direction + "-" + UUID.randomUUID().toString().replace("-", "");
        String code;
        try {
            code = otpDeliveryService.verificationCode(user.getCountryCode());
        } catch (RuntimeException ex) {
            throw new PreWriteRejection(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        userMapper.invalidateOpenSecurityOtpChallenges(userId);
        if (userMapper.createLoginOtpChallenge(userId, challengeNo, code, TWO_FACTOR_OTP_TTL_MINUTES) != 1) {
            throw new IllegalStateException("USER_TWO_FACTOR_CHALLENGE_NOT_CREATED");
        }
        try {
            otpDeliveryService.deliver(user.getCountryCode(), user.getPhone(), challengeNo, code,
                    TWO_FACTOR_OTP_TTL_MINUTES);
        } catch (RuntimeException ex) {
            userMapper.invalidateOpenSecurityOtpChallenges(userId);
            throw new PreWriteRejection(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        recordRequired(userId, "USER_TWO_FACTOR_CHALLENGE_SENT", "USER_SECURITY", String.valueOf(userId),
                Map.of("targetEnabled", request.enabled(), "challengeNo", shortId(challengeNo)));
        return Map.of("challengeNo", challengeNo, "expiresInSeconds", TWO_FACTOR_OTP_TTL_MINUTES * 60,
                "phoneMasked", maskPhone(user.getPhone()));
    }

    @Transactional(noRollbackFor = PreWriteRejection.class)
    public AppSecurityMutationResponse updateTwoFactor(Long userId, AppTwoFactorUpdateRequest request) {
        if (userId == null || userId <= 0) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
        if (request == null || request.enabled() == null || !StringUtils.hasText(request.currentPassword())) {
            throw new BizException(422, "CURRENT_PASSWORD_REQUIRED");
        }
        verifyCurrentPassword(userId, request.currentPassword(), "TWO_FACTOR_UPDATE");
        boolean enabled = request.enabled();
        String expectedPrefix = enabled ? "SEC2FA-E-" : "SEC2FA-D-";
        if (!StringUtils.hasText(request.challengeNo()) || !request.challengeNo().startsWith(expectedPrefix)
                || !StringUtils.hasText(request.code()) || !request.code().trim().matches("\\d{6}")) {
            throw new BizException(422, "USER_TWO_FACTOR_OTP_REQUIRED");
        }
        if (userMapper.consumeValidSecurityOtp(userId, request.challengeNo().trim(), request.code().trim()) != 1) {
            throw new PreWriteRejection(401, "USER_TWO_FACTOR_OTP_INVALID_OR_EXPIRED");
        }
        if (securityMapper.twoFactorEnabled(userId) == enabled) {
            return AppSecurityMutationResponse.twoFactor(enabled);
        }
        if (securityMapper.upsertTwoFactor(userId, enabled) < 1) {
            throw new IllegalStateException("USER_TWO_FACTOR_STATE_CHANGED");
        }
        recordRequired(userId, enabled ? "USER_TWO_FACTOR_ENABLED" : "USER_TWO_FACTOR_DISABLED",
                "USER_SECURITY", String.valueOf(userId), Map.of("twoFactorEnabled", enabled));
        return AppSecurityMutationResponse.twoFactor(enabled);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> accountDeletionStatus(Long userId, String currentSessionId) {
        requireContext(userId, currentSessionId);
        Map<String, Object> row = securityMapper.latestAccountDeletionRequest(userId);
        return row == null ? Map.of("status", "NONE") : immutableMap(row);
    }

    @Transactional(noRollbackFor = PreWriteRejection.class)
    public Map<String, Object> requestAccountDeletion(
            Long userId,
            String currentSessionId,
            String idempotencyKey,
            AppAccountDeletionRequest request) {
        requireContext(userId, currentSessionId);
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > 160) {
            throw new BizException(422, "IDEMPOTENCY_KEY_INVALID");
        }
        if (request == null || !"DELETE".equals(request.confirmation())) {
            throw new BizException(422, "ACCOUNT_DELETION_CONFIRMATION_REQUIRED");
        }
        verifyCurrentPassword(userId, request.currentPassword(), "ACCOUNT_DELETION_REQUEST");
        Map<String, Object> existing = securityMapper.latestAccountDeletionRequest(userId);
        if (existing != null && Set.of("REQUESTED", "IN_REVIEW", "BLOCKED", "COMPLETED").contains(
                String.valueOf(existing.get("status")))) {
            return immutableMap(existing);
        }
        String requestNo = "ADR-" + UUID.randomUUID().toString().replace("-", "");
        int inserted = securityMapper.insertAccountDeletionRequest(
                requestNo, userId, idempotencyKey.trim());
        Map<String, Object> authoritative = securityMapper.accountDeletionRequestForUpdate(
                userId, idempotencyKey.trim());
        if (authoritative == null && inserted == 0) {
            authoritative = securityMapper.latestAccountDeletionRequest(userId);
        }
        if (authoritative == null) {
            throw new IllegalStateException("ACCOUNT_DELETION_REQUEST_RESULT_UNKNOWN");
        }
        if (inserted == 1) {
            recordRequired(userId, "USER_ACCOUNT_DELETION_REQUESTED", "USER_ACCOUNT_DELETION",
                    String.valueOf(authoritative.get("requestNo")),
                    Map.of("status", "REQUESTED", "currentSessionRevoked", false));
        }
        return immutableMap(authoritative);
    }

    @Transactional
    public Map<String, Object> cancelAccountDeletion(
            Long userId, String currentSessionId, String idempotencyKey, AppAccountDeletionCancelRequest request) {
        requireContext(userId, currentSessionId);
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > 160) {
            throw new BizException(422, "IDEMPOTENCY_KEY_INVALID");
        }
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new BizException(422, "ACCOUNT_DELETION_VERSION_REQUIRED");
        }
        String reason = StringUtils.hasText(request.reason()) ? request.reason().trim() : "USER_REQUESTED_CANCEL";
        if (reason.length() > 255) throw new BizException(422, "ACCOUNT_DELETION_REASON_INVALID");
        Map<String, Object> current = securityMapper.latestAccountDeletionRequest(userId);
        if (current == null) throw new BizException(404, "ACCOUNT_DELETION_REQUEST_NOT_FOUND");
        String status = String.valueOf(current.get("status"));
        if ("CANCELLED".equals(status)) return immutableMap(current);
        AccountDeletionStateMachine.requireTransition(status, "CANCELLED", reason);
        long version = ((Number) current.get("version")).longValue();
        if (version != request.expectedVersion()) throw new BizException(409, "ACCOUNT_DELETION_VERSION_CONFLICT");
        String requestNo = String.valueOf(current.get("requestNo"));
        if (securityMapper.transitionAccountDeletion(requestNo, status, "CANCELLED", version, reason, null) != 1) {
            throw new BizException(409, "ACCOUNT_DELETION_CONCURRENT_UPDATE");
        }
        Map<String, Object> updated = securityMapper.accountDeletionRequestForUpdate(userId, idempotencyKey.trim());
        if (updated == null) updated = securityMapper.latestAccountDeletionRequest(userId);
        recordRequired(userId, "USER_ACCOUNT_DELETION_CANCELLED", "USER_ACCOUNT_DELETION", requestNo,
                Map.of("status", "CANCELLED", "reason", reason));
        return immutableMap(updated);
    }

    private Map<String, Object> immutableMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private AppSecurityStateResponse.Session toResponse(UserSessionEntity row, String currentSessionId) {
        LocalDateTime lastActiveAt = row.getLastActiveAt() == null ? row.getCreatedAt() : row.getLastActiveAt();
        return new AppSecurityStateResponse.Session(
                row.getRefreshTokenId(),
                StringUtils.hasText(row.getDeviceName()) ? row.getDeviceName().trim() : "Nexion App / H5",
                maskIp(row.getClientIp()),
                lastActiveAt,
                currentSessionId.equals(row.getRefreshTokenId()));
    }

    private String maskIp(String value) {
        if (!StringUtils.hasText(value)) return "";
        String ip = value.trim();
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) return ip.substring(0, lastDot) + ".*";
        String[] parts = ip.split(":");
        if (parts.length > 2) return String.join(":", java.util.Arrays.copyOf(parts, Math.min(4, parts.length))) + ":*";
        return "";
    }

    private String maskPhone(String value) {
        if (!StringUtils.hasText(value)) return "";
        String phone = value.trim();
        if (phone.length() <= 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }

    private void requireContext(Long userId, String currentSessionId) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(currentSessionId)) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
    }

    private boolean safeMatches(String rawPassword, String encodedPassword) {
        try {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean strongPassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < 8 || password.length() > MAX_PASSWORD_LENGTH) return false;
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) return false;
        if (password.matches(".*(.)\\1{5,}.*")) return false;
        return !WEAK_PASSWORDS.contains(password.toLowerCase(Locale.ROOT));
    }

    private boolean validRawPassword(String password) {
        return StringUtils.hasText(password) && password.length() <= MAX_PASSWORD_LENGTH;
    }

    private String verifyCurrentPassword(Long userId, String rawPassword, String operation) {
        if (!verificationGuard.allowed(userId, operation)) {
            throw new PreWriteRejection(429, "USER_SECURITY_VERIFICATION_RATE_LIMITED");
        }
        if (!validRawPassword(rawPassword)) {
            rejectCurrentPassword(userId, operation);
        }
        String passwordHash = securityMapper.passwordHashForUpdate(userId);
        if (!StringUtils.hasText(passwordHash) || !safeMatches(rawPassword, passwordHash)) {
            rejectCurrentPassword(userId, operation);
        }
        verificationGuard.clear(userId);
        return passwordHash;
    }

    private void rejectCurrentPassword(Long userId, String operation) {
        AppUserSecurityVerificationGuard.VerificationFailure failure =
                verificationGuard.recordFailure(userId, operation);
        if (failure.rateLimited()) {
            throw new PreWriteRejection(429, "USER_SECURITY_VERIFICATION_RATE_LIMITED");
        }
        throw new PreWriteRejection(401, "CURRENT_PASSWORD_INVALID");
    }

    private String shortId(String id) {
        return id.length() <= 12 ? id : id.substring(0, 6) + "..." + id.substring(id.length() - 4);
    }

    private void recordRequired(Long userId, String action, String resourceType, String resourceId, Object detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .userId(userId)
                .actorId(userId)
                .actorType("USER")
                .actorUsername("user:" + userId)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    /**
     * A rejection raised before a security mutation writes business state. Committing only this
     * subtype preserves the verification counter/audit (or a successful guard clear) while an
     * audit/outbox failure after a real mutation still rolls the whole transaction back.
     */
    static final class PreWriteRejection extends BizException {
        PreWriteRejection(int code, String message) {
            super(code, message);
        }
    }
}
