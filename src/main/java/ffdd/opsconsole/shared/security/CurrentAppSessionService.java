package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CurrentAppSessionService {
    private final AuthSessionMapper mapper;
    private final ffdd.opsconsole.platform.facade.PlatformConfigFacade config;
    private final UserAccountBlocklistVerifier blocklist;
    private final ffdd.opsconsole.user.mapper.UserOpsMapper users;
    private final org.springframework.core.env.Environment environment;

    /** Credential-free read for authorized C5 callers, using the same account and idle-session rules. */
    @Transactional(readOnly = true)
    public java.util.List<AuthSessionMapper.SessionSummary> adminSummary(long userId) {
        var user=users.selectById(userId);
        var audience=UserAuthEnvironment.resolve(environment).orElse(null);
        if(user==null || !Integer.valueOf(0).equals(user.getIsDeleted()) || !"ACTIVE".equals(user.getStatus())
                || audience==null || !audience.acceptsSandbox(user.getSandbox()) || blocklist.isBlocked(userId)
                || mapper.passwordResetRequired(userId))return java.util.List.of();
        return mapper.adminSessionSummary(userId,idleDays());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireExecutionOwner(long userId, String installationId) {
        requireEligible(userId);
        if (mapper.countExecutionOwner(userId, installationId, idleDays()) != 1)
            throw new BizException(409, "PHONE_EXECUTION_SESSION_REQUIRED");
    }

    /** Transaction remains open through the guarded business write. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UserSessionEntity requireCurrent(Authentication auth, String installationId, boolean executionRequired) {
        UserSessionEntity session = requireSession(auth);
        if (!"APP".equals(session.getSessionGroup())) throw new BizException(403, "APP_SESSION_REQUIRED");
        if (installationId == null || !installationId.equals(session.getInstallationId()))
            throw new BizException(409, "INSTALLATION_MISMATCH");
        if (executionRequired && !Boolean.TRUE.equals(session.getExecutionSupported()))
            throw new BizException(403, "PHONE_EXECUTION_UNSUPPORTED");
        return session;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserSessionEntity requireSession(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType")) || !(details.get("sessionId") instanceof String id))
            throw new BizException(403, "USER_AUTH_REQUIRED");
        long userId;
        try { userId = Long.parseLong(String.valueOf(auth.getPrincipal())); }
        catch (NumberFormatException invalid) { throw new BizException(403, "USER_AUTH_REQUIRED"); }
        if (mapper.lockAccount(userId) == null) throw new BizException(403, "USER_AUTH_REQUIRED");
        requireEligible(userId);
        UserSessionEntity session = mapper.findAuthorizedSession(id, userId, idleDays());
        if (session == null) throw new BizException(401, "SESSION_REPLACED");
        return session;
    }
    private void requireEligible(long userId) {
        var user = users.findLoginUserForUpdate(userId);
        var audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (user == null || !Integer.valueOf(0).equals(user.getIsDeleted()) || !"ACTIVE".equals(user.getStatus())
                || audience == null || !audience.acceptsSandbox(user.getSandbox()) || blocklist.isBlocked(userId)
                || users.isPasswordResetRequired(userId)) throw new BizException(403, "USER_AUTH_REQUIRED");
    }

    private int idleDays() {
        try {
            int value = config.activeValue("auth.session.idle_ttl_days").map(Integer::parseInt).orElse(30);
            return value >= 7 && value <= 90 ? value : 30;
        } catch (NumberFormatException invalid) { return 30; }
    }
}
