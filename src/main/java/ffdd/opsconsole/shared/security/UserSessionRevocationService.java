package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Explicit security actions serialize with authentication and stop only a revoked App owner. */
@Service
@RequiredArgsConstructor
public class UserSessionRevocationService {
    private final AuthSessionMapper sessions;
    private final PhoneExecutionSessionContract phone;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockAccount(long userId) { sessions.lockSecurityAccount(userId); }

    @Transactional
    public int revokeAll(long userId) {
        sessions.lockSecurityAccount(userId);
        int count = sessions.revokeAllUserSessions(userId);
        sessions.endAllChains(userId, "SECURITY_REVOKED");
        phone.endedApp(userId);
        return count;
    }

    @Transactional
    public int revokeOthers(long userId, String currentSessionId) {
        sessions.lockSecurityAccount(userId);
        boolean stop = sessions.countAppOutsideChain(userId, currentSessionId) > 0;
        int count = sessions.revokeOtherUserSessions(userId, currentSessionId);
        sessions.endOtherChains(userId, currentSessionId);
        if (stop) phone.endedApp(userId);
        return count;
    }

    /**
     * Revokes one installation group (or one empty-installation chain) and all
     * successor rows in it. The caller has already rejected the current group.
     */
    @Transactional
    public int revokeGroup(long userId, String targetSessionId) {
        sessions.lockSecurityAccount(userId);
        UserSessionEntity target = sessions.findSession(targetSessionId);
        if (target == null || !Long.valueOf(userId).equals(target.getUserId())) return 0;
        if (StringUtils.hasText(target.getInstallationId())) {
            boolean stop = "APP".equals(target.getSessionGroup())
                    && sessions.countFixedAppContextInInstallationGroup(userId, target.getInstallationId()) > 0;
            int count = sessions.revokeInstallationGroup(userId, target.getSessionGroup(), target.getInstallationId());
            sessions.endInstallationGroup(userId, target.getSessionGroup(), target.getInstallationId());
            if (stop && count > 0) phone.endedApp(userId);
            return count;
        }
        boolean stop = "APP".equals(target.getSessionGroup())
                && (StringUtils.hasText(target.getSessionChainId())
                    ? sessions.countFixedAppContextInChain(userId, target.getSessionChainId()) > 0
                    : sessions.countFixedContext(targetSessionId, userId) == 1);
        int count = sessions.revokeOwnedUserSession(userId, targetSessionId);
        sessions.endChain(target.getSessionChainId(), "SECURITY_REVOKED");
        if (stop) phone.endedApp(userId);
        return count;
    }

    /**
     * Keeps the whole current installation group; only empty-installation
     * sessions fall back to the original per-chain behavior.
     */
    @Transactional
    public int revokeOthersInGroup(long userId, String currentSessionId) {
        sessions.lockSecurityAccount(userId);
        UserSessionEntity current = sessions.findOwnedSession(currentSessionId, userId);
        if (current == null || !StringUtils.hasText(current.getInstallationId())) {
            boolean stop = sessions.countAppOutsideChain(userId, currentSessionId) > 0;
            int count = sessions.revokeOtherUserSessions(userId, currentSessionId);
            sessions.endOtherChains(userId, currentSessionId);
            if (stop) phone.endedApp(userId);
            return count;
        }
        boolean stop = sessions.countAppOutsideInstallationGroup(
                userId, current.getSessionGroup(), current.getInstallationId()) > 0;
        int count = sessions.revokeOtherInstallationGroups(
                userId, current.getSessionGroup(), current.getInstallationId());
        sessions.endOtherInstallationGroups(userId, current.getSessionGroup(), current.getInstallationId());
        if (stop) phone.endedApp(userId);
        return count;
    }

    public static String sessionGroupKey(UserSessionEntity session) {
        if (session == null) return null;
        if (StringUtils.hasText(session.getInstallationId())) {
            return "INSTALL:" + session.getSessionGroup() + ":" + session.getInstallationId();
        }
        String chain = StringUtils.hasText(session.getSessionChainId())
                ? session.getSessionChainId() : session.getRefreshTokenId();
        return "EMPTY:" + session.getSessionGroup() + ":" + chain;
    }

    @Transactional
    public int revokeOwned(long userId, String sessionId) {
        return revokeOwned(userId, sessionId, "SECURITY_REVOKED");
    }

    @Transactional
    public int expireOwned(long userId, String sessionId) {
        return revokeOwned(userId, sessionId, "SESSION_EXPIRED");
    }

    private int revokeOwned(long userId, String sessionId, String reason) {
        sessions.lockSecurityAccount(userId);
        var target = sessions.findSession(sessionId);
        if (target == null || !Long.valueOf(userId).equals(target.getUserId())) return 0;
        boolean stop = "APP".equals(target.getSessionGroup()) && sessions.countFixedContext(sessionId, userId) == 1;
        int count = sessions.revokeOwnedUserSession(userId, sessionId);
        sessions.endChain(target.getSessionChainId(), reason);
        if (stop) phone.endedApp(userId);
        return count;
    }
}
