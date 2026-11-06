package ffdd.opsconsole.content.realtime;

import ffdd.opsconsole.shared.security.*;
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
    private final ConversationRepository conversations;
    private final ProductionSupportPathGuard production;
    private final ffdd.opsconsole.content.application.SupportOwnershipService ownership;
    private final ffdd.opsconsole.content.mapper.SupportBindingMapper bindings;
    private final ffdd.opsconsole.content.application.SupportSessionService sessions;

    public Authentication authenticate(String token, String audience) {
        return requireAudience(authentication.authenticateSocketToken(token), audience);
    }

    public Authentication revalidate(String token, String audience) {
        return requireAudience(authentication.revalidateSocketToken(token), audience);
    }

    public Authentication reauthenticate(Authentication current, String token, String audience) {
        if (!"USER".equals(audience)) throw new BizException(401, "SOCKET_REAUTH_REJECTED");
        Authentication next = revalidate(token, audience);
        if (!current.getName().equals(next.getName()) || !authentication.isActiveSocketRefreshSuccessor(
                Long.valueOf(next.getName()), (String) ((Map<?, ?>) current.getDetails()).get("sessionId"),
                (String) ((Map<?, ?>) next.getDetails()).get("sessionId"))) {
            throw new BizException(401, "SOCKET_REAUTH_REJECTED");
        }
        return next;
    }

    private Authentication requireAudience(Authentication auth, String audience) {
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

    /** Customer support remains available before onboarding; admin writes still need permission. */
    public void write(Authentication auth, String audience) {
        if (audience.equals("ADMIN")) {
            if (!has(auth, "service_m3_write")) throw new BizException(403, "SOCKET_PERMISSION_DENIED");
        }
    }

    public boolean canRead(Authentication auth, String audience, String conversationNo) {
        return customerId(conversationNo).map(id -> "ADMIN".equals(audience)
                ? has(auth, "service_m3_read") && canReadCustomer(auth.getName(), id)
                : id.toString().equals(auth.getName())).orElse(false);
    }

    boolean canReadCustomer(String admin, Long customerId) {
        return ownership.canRead(Long.valueOf(admin), customerId);
    }

    Optional<Long> customerId(String no) {
        if (no == null || !no.matches("[A-Za-z0-9_-]{1,100}")) return Optional.empty();
        return conversations.findByConversationNo(no).map(c -> c.userId());
    }

    public boolean canWrite(Authentication auth,String audience,String no) {
        if (!canRead(auth,audience,no)) return false;
        return !"ADMIN".equals(audience) || (has(auth,"service_m3_write")
                && sessions.canType(ownership.actorId(auth),ownership.conversationCustomer(no)));
    }

    public static boolean has(Authentication auth, String permission) {
        return auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    /** Loaded once per watched conversation in a presence batch; never retained across batches. */
    public Optional<Participants> participants(String no) {
        return customerId(no).map(id -> {
            var current=sessions.holders(List.of(id)).get(id);
            Long peer=current==null?bindings.currentAgent(id):current.adminId();
            return new Participants(String.valueOf(id),String.valueOf(peer),ownership);
        });
    }

    public record Participants(String userId, String agentId, ffdd.opsconsole.content.application.SupportOwnershipService ownership) {
        public boolean canRead(Authentication auth, String audience) {
            return audience.equals("ADMIN") ? has(auth, "service_m3_read") && ownership.canRead(ownership.actorId(auth), Long.valueOf(userId)) : userId.equals(auth.getName());
        }
        public String peerKey(String viewerAudience) {
            return viewerAudience.equals("ADMIN") ? "USER:" + userId : "ADMIN:" + agentId;
        }
    }

    boolean contributesPresence(Authentication auth, String audience) {
        if ("USER".equals(audience)) return true;
        if (!has(auth, "service_m3_write")) return false;
        Long admin = ownership.actorId(auth);
        return bindings.eligibleAgent(admin) == 1 || ownership.supervisor(admin);
    }
}
