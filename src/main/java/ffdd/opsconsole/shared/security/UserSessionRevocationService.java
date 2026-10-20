package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
