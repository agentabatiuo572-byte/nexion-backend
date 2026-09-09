package ffdd.opsconsole.content.realtime;

import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.security.core.Authentication;

/** No independent socket identity or permission cache. Recheck the current session. */
@Component
@RequiredArgsConstructor
public class ConversationSocketAccess {
    private final JwtAuthenticationFilter authentication;
    private final AdminRbacAuthorizationFilter adminGate;
    private final UserAccountBlocklistVerifier blocklist;
    private final UserBusinessWriteGateFilter userGate;
    private final ConversationRepository conversations;
    private final ProductionSupportPathGuard production;

    public Authentication authenticate(String token, String audience) {
        Authentication auth = authentication.authenticateSocketToken(token);
        Object type = ((Map<?, ?>) auth.getDetails()).get("subjectType");
        if (!audience.equals(type)) throw new BizException(403, "SOCKET_SUBJECT_MISMATCH");
        if (audience.equals("ADMIN")) {
            if (adminGate.passwordChangeRequired(auth) || !has(auth, "service_m3_read")) throw new BizException(403, "SOCKET_PERMISSION_DENIED");
            production.requireOpsWriteAllowed();
        } else {
            Long id = Long.valueOf(auth.getName());
            if (blocklist.isBlocked(id)) throw new BizException(403, "ACCOUNT_BLOCKLISTED");
            production.requireAllowed(id);
        }
        return auth;
    }

    public void write(Authentication auth, String audience) {
        if (audience.equals("ADMIN")) {
            if (!has(auth, "service_m3_write")) throw new BizException(403, "SOCKET_PERMISSION_DENIED");
        } else {
            ApiResult<Void> blocked = userGate.businessWriteBlock(Long.valueOf(auth.getName()));
            if (blocked != null) throw new BizException(blocked.getCode(), blocked.getMessage());
        }
    }

    public boolean canRead(Authentication auth, String audience, String conversationNo) {
        return participants(conversationNo).map(p -> p.canRead(auth, audience)).orElse(false);
    }

    public static boolean has(Authentication auth, String permission) {
        return auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    /** Loaded once per watched conversation in a presence batch; never retained across batches. */
    public Optional<Participants> participants(String no) {
        if (no == null || !no.matches("[A-Za-z0-9_-]{1,100}")) return Optional.empty();
        return conversations.findByConversationNo(no).map(c -> new Participants(String.valueOf(c.userId()), c.ownerAgentId()));
    }

    public record Participants(String userId, String agentId) {
        public boolean canRead(Authentication auth, String audience) {
            return audience.equals("ADMIN") ? has(auth, "service_m3_read") : userId.equals(auth.getName());
        }
        public String peerKey(String viewerAudience) {
            return viewerAudience.equals("ADMIN") ? "USER:" + userId : "ADMIN:" + agentId;
        }
    }

    public Set<String> presenceKeys(Authentication auth, String audience) {
        if (audience.equals("USER")) return Set.of("USER:" + auth.getName());
        if (!has(auth, "service_m3_write")) return Set.of();
        Set<String> keys = new HashSet<>();
        keys.add("ADMIN:" + auth.getName());
        Object username = ((Map<?, ?>) auth.getDetails()).get("username");
        if (username != null) keys.add("ADMIN:" + username);
        return keys;
    }
}
