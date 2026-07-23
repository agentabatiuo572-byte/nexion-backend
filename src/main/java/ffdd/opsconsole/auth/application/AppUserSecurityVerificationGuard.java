package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable per-user throttle for current-password checks on authenticated security endpoints. */
@Service
@RequiredArgsConstructor
public class AppUserSecurityVerificationGuard {
    static final int MAX_FAILURES = 5;
    static final int WINDOW_MINUTES = 15;

    private final UserLoginGuardMapper loginGuardMapper;
    private final AuditLogService auditLogService;

    @Transactional
    public boolean allowed(Long userId, String operation) {
        LocalDateTime now = LocalDateTime.now();
        String key = key(userId);
        loginGuardMapper.initialize(key, now);
        loginGuardMapper.bindUser(key, userId);
        UserLoginGuardRecord guard = loginGuardMapper.lock(key);
        boolean allowed = guard == null || guard.getLockedUntil() == null || !guard.getLockedUntil().isAfter(now);
        if (!allowed) {
            auditRejected(userId, operation, guard.getFailedCount(), true, guard.getLockedUntil(), true);
        }
        return allowed;
    }

    @Transactional
    public VerificationFailure recordFailure(Long userId, String operation) {
        LocalDateTime now = LocalDateTime.now();
        String key = key(userId);
        loginGuardMapper.initialize(key, now);
        loginGuardMapper.bindUser(key, userId);
        UserLoginGuardRecord guard = loginGuardMapper.lock(key);
        boolean newWindow = guard == null || guard.getWindowStartedAt() == null
                || guard.getWindowStartedAt().plusMinutes(WINDOW_MINUTES).isBefore(now);
        int failedCount = newWindow ? 1 : guard.getFailedCount() + 1;
        LocalDateTime windowStartedAt = newWindow ? now : guard.getWindowStartedAt();
        LocalDateTime lockedUntil = failedCount >= MAX_FAILURES ? now.plusMinutes(WINDOW_MINUTES) : null;
        loginGuardMapper.recordFailure(key, failedCount, windowStartedAt, lockedUntil);

        auditRejected(userId, operation, failedCount, lockedUntil != null, lockedUntil, false);
        return new VerificationFailure(failedCount, lockedUntil != null);
    }

    private void auditRejected(
            Long userId,
            String operation,
            int failedCount,
            boolean rateLimited,
            LocalDateTime lockedUntil,
            boolean blocked) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", operation);
        detail.put("failedCount", failedCount);
        detail.put("rateLimited", rateLimited);
        detail.put("blocked", blocked);
        if (lockedUntil != null) detail.put("lockedUntil", lockedUntil.toString());
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("USER_SECURITY_PASSWORD_VERIFICATION_REJECTED")
                .resourceType("USER_SECURITY")
                .resourceId(String.valueOf(userId))
                .userId(userId)
                .actorId(userId)
                .actorType("USER")
                .actorUsername("user:" + userId)
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    @Transactional
    public void clear(Long userId) {
        loginGuardMapper.clear(key(userId));
    }

    private String key(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("USER_ID_INVALID");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("security-password:" + userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record VerificationFailure(int failedCount, boolean rateLimited) {
    }
}
