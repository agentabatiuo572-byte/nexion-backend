package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.dto.AppPasswordChangeRequest;
import ffdd.opsconsole.auth.dto.AppSecurityMutationResponse;
import ffdd.opsconsole.auth.dto.AppSecurityStateResponse;
import ffdd.opsconsole.auth.dto.AppTwoFactorUpdateRequest;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private final AppUserSecurityMapper securityMapper;
    private final AuthSessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final UserOtpDeliveryService otpDeliveryService;
    private final AppUserSecurityVerificationGuard verificationGuard;

    @Transactional(readOnly = true)
    public AppSecurityStateResponse overview(Long userId, String currentSessionId) {
        requireContext(userId, currentSessionId);
        List<AppSecurityStateResponse.Session> sessions = sessionMapper
                .listActiveUserSessions(userId, SESSION_IDLE_DAYS).stream()
                .map(row -> toResponse(row, currentSessionId))
                .toList();
        return new AppSecurityStateResponse(
                securityMapper.twoFactorEnabled(userId),
                securityMapper.passwordChangedAt(userId),
                sessions);
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
    public AppSecurityMutationResponse updateTwoFactor(Long userId, AppTwoFactorUpdateRequest request) {
        if (userId == null || userId <= 0) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
        if (request == null || request.enabled() == null || !StringUtils.hasText(request.currentPassword())) {
            throw new BizException(422, "CURRENT_PASSWORD_REQUIRED");
        }
        verifyCurrentPassword(userId, request.currentPassword(), "TWO_FACTOR_UPDATE");
        boolean enabled = request.enabled();
        if (enabled && !otpDeliveryService.available()) {
            throw new PreWriteRejection(503, "USER_OTP_DELIVERY_UNAVAILABLE");
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
