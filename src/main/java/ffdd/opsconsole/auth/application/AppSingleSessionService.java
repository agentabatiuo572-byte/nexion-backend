package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.AppLoginAttemptMapper;
import ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One transaction owns the account sequence, revocation, phone stop and attempt receipt. */
@Service
@RequiredArgsConstructor
public class AppSingleSessionService {
    private final AuthSessionMapper sessions;
    private final AppLoginAttemptMapper attempts;
    private final JwtTokenProvider tokens;
    private final PhoneExecutionSessionContract phone;
    private final AuditLogService audit;
    private final ffdd.opsconsole.shared.outbox.EventOutboxService outbox;
    private final ffdd.opsconsole.platform.facade.PlatformConfigFacade config;
    private final ffdd.opsconsole.user.mapper.UserOpsMapper users;
    private final ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier blocklist;
    private final org.springframework.core.env.Environment environment;

    public boolean hasAttempt(String attemptId) { return attempts.find(attemptId) != null; }

    public record Issued(UserSessionEntity session, String refreshToken, boolean original, String status) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public Issued issue(long userId, AppLoginContext context, String clientAddress, int refreshDays) {
        context.validate();
        if (sessions.lockAccount(userId) == null) throw new BizException(403, "USER_AUTH_REQUIRED");
        requireEligible(userId);
        var existing = attempts.findLocked(context.loginAttemptId());
        if (existing != null) {
            validateAttempt(existing, context);
            if (existing.userId() != userId) throw new BizException(409, "LOGIN_ATTEMPT_CONTEXT_MISMATCH");
            return receipt(existing, context);
        }
        String raw = refreshToken(context);
        UserSessionEntity session = new UserSessionEntity();
        session.setUserId(userId);
        session.setRefreshTokenId(hash(raw));
        session.setSessionChainId(UUID.randomUUID().toString());
        session.setSessionGroup(context.group().name());
        session.setInstallationId(context.installationId());
        session.setExecutionSupported(context.executionSupported());
        session.setLoginAttemptId(context.loginAttemptId());
        session.setDeviceName(context.group().deviceName());
        session.setClientIp(clientAddress);
        LocalDateTime now = LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE);
        session.setLastActiveAt(now);
        session.setExpiresAt(now.plusDays(refreshDays));
        session.setIsDeleted(0);
        long previousVersion = 0;
        String previousInstallation = "";
        if (context.group() == AppSessionSurface.APP) {
            sessions.initializeAppState(userId);
            previousVersion = sessions.lockAppVersion(userId);
            previousInstallation = sessions.appInstallation(userId);
            long version = Math.addExact(previousVersion, 1L);
            session.setAppSessionVersion(version);
            sessions.replaceAppSessions(userId);
            if (sessions.advanceAppState(userId, version, session.getSessionChainId(), context.installationId(),
                    context.executionSupported()) != 1) throw new IllegalStateException("APP_SESSION_ADVANCE_FAILED");
        }
        if (sessions.insert(session) != 1) throw new IllegalStateException("APP_SESSION_INSERT_FAILED");
        if (context.group() == AppSessionSurface.APP)
            phone.authenticatedApp(userId, context.installationId(), context.executionSupported());
        if (attempts.insert(new AppLoginAttemptMapper.Attempt(context.loginAttemptId(), userId,
                fingerprint(context), hash(context.recoveryProof()), session.getRefreshTokenId())) != 1)
            throw new IllegalStateException("LOGIN_ATTEMPT_INSERT_FAILED");
        audit.recordRequired(AuditLogWriteRequest.builder().action("AUTH_SESSION_ISSUED")
                .resourceType("USER_SESSION").resourceId(session.getSessionChainId()).userId(userId)
                .actorType("USER").actorId(userId).result("SUCCESS").riskLevel("LOW")
                .detail(Map.of("sessionGroup", session.getSessionGroup(), "loginAttemptId", context.loginAttemptId(),
                        "appSessionVersion", session.getAppSessionVersion() == null ? 0L : session.getAppSessionVersion()))
                .build());
        if (context.group() == AppSessionSurface.APP) {
            Map<String, Object> fact = Map.of("userId", userId, "oldSessionVersion", previousVersion,
                    "newSessionVersion", session.getAppSessionVersion(), "oldInstallationId",
                    previousInstallation == null ? "" : previousInstallation, "newInstallationId", context.installationId(),
                    "operationId", context.loginAttemptId(), "committedAt", now.toString());
            audit.recordRequired(AuditLogWriteRequest.builder().action("account.app_session_replaced")
                    .resourceType("USER_SESSION").resourceId(session.getSessionChainId()).userId(userId)
                    .actorType("USER").actorId(userId).result("SUCCESS").riskLevel("MEDIUM").detail(fact).build());
            outbox.publish("USER_SECURITY", String.valueOf(userId), "account.app_session_replaced", fact);
        }
        return new Issued(session, raw, false, "ACTIVE");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Issued recover(AppLoginContext context) {
        context.validate();
        var attempt = attempts.find(context.loginAttemptId());
        // The ID alone reveals neither identity nor whether this attempt succeeded.
        if (attempt == null) throw new BizException(401, "LOGIN_ATTEMPT_RECOVERY_INVALID");
        validateAttempt(attempt, context);
        if (sessions.lockAccount(attempt.userId()) == null) throw new BizException(403, "USER_AUTH_REQUIRED");
        requireEligible(attempt.userId());
        return receipt(attempts.findLocked(context.loginAttemptId()), context);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Issued recoverCompletedForUser(long userId, AppLoginContext context) {
        context.validate();
        if (sessions.lockAccount(userId) == null) throw new BizException(403, "USER_AUTH_REQUIRED");
        var existing = attempts.findLocked(context.loginAttemptId());
        if (existing == null) return null;
        validateAttempt(existing, context);
        if (existing.userId() != userId) throw new BizException(409, "LOGIN_ATTEMPT_CONTEXT_MISMATCH");
        requireEligible(userId);
        return receipt(existing, context);
    }

    private Issued receipt(AppLoginAttemptMapper.Attempt attempt, AppLoginContext context) {
        UserSessionEntity session = sessions.findRefreshForUpdate(attempt.sessionId());
        if (session == null) throw new IllegalStateException("LOGIN_ATTEMPT_SESSION_MISSING");
        if (sessions.countFixedContext(attempt.sessionId(), attempt.userId()) == 0)
            return new Issued(session, null, true, session.getSessionEndedReason() == null
                    ? "SESSION_REPLACED" : session.getSessionEndedReason());
        if (session.getRevokedAt() != null || session.getRotationRedeemedAt() != null)
            return new Issued(session, null, true, "SESSION_ROTATED");
        if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE)))
            return new Issued(session, null, true, "SESSION_EXPIRED");
        LocalDateTime lastActive = session.getLastActiveAt() == null ? session.getCreatedAt() : session.getLastActiveAt();
        if (lastActive == null || !lastActive.plusDays(idleDays()).isAfter(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE)))
            return new Issued(session, null, true, "SESSION_EXPIRED");
        return new Issued(session, refreshToken(context), true, "ACTIVE");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void logout(UserSessionEntity session) {
        if ("APP".equals(session.getSessionGroup())
                && sessions.countFixedContext(session.getRefreshTokenId(), session.getUserId()) == 1)
            phone.endedApp(session.getUserId());
        sessions.endChain(session.getSessionChainId(), "CURRENT_LOGOUT");
        sessions.revokeRefreshChain(session.getSessionChainId());
    }

    private void requireEligible(long userId) {
        var user = users.findLoginUserForUpdate(userId);
        if (user == null || !Integer.valueOf(0).equals(user.getIsDeleted()) || !"ACTIVE".equals(user.getStatus())
                || blocklist.isBlocked(userId) || users.isPasswordResetRequired(userId))
            throw new BizException(403, "USER_REFRESH_NOT_ALLOWED");
        if (UserAuthEnvironmentPolicy.evaluate(environment, user) != UserAuthEnvironmentPolicy.Decision.ALLOW)
            throw new BizException(403, "USER_AUTH_ENVIRONMENT_FORBIDDEN");
    }

    private int idleDays() {
        try {
            int value = config.activeValue("auth.session.idle_ttl_days").map(Integer::parseInt).orElse(30);
            return value >= 7 && value <= 90 ? value : 30;
        } catch (NumberFormatException invalid) { return 30; }
    }

    private void validateAttempt(AppLoginAttemptMapper.Attempt attempt, AppLoginContext context) {
        if (!MessageDigest.isEqual(attempt.recoveryHash().getBytes(StandardCharsets.US_ASCII),
                hash(context.recoveryProof()).getBytes(StandardCharsets.US_ASCII)))
            throw new BizException(401, "LOGIN_ATTEMPT_RECOVERY_INVALID");
        if (!attempt.contextHash().equals(fingerprint(context)))
            throw new BizException(409, "LOGIN_ATTEMPT_CONTEXT_MISMATCH");
    }

    private String refreshToken(AppLoginContext context) {
        return tokens.refreshSuccessor(context.recoveryProof(), "LOGIN_ATTEMPT_V1:" + context.loginAttemptId());
    }

    private static String fingerprint(AppLoginContext context) {
        // Length-prefix values: delimiter-bearing installation/challenge strings cannot collide.
        StringBuilder value = new StringBuilder();
        for (String part : new String[]{context.group().name(), context.installationId(),
                Boolean.toString(context.executionSupported()), context.authMethod(), context.authReference()})
            value.append(part.length()).append(':').append(part);
        return hash(value.toString());
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
