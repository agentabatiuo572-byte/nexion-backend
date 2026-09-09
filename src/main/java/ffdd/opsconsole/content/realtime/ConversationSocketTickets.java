package ffdd.opsconsole.content.realtime;

import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ffdd.opsconsole.shared.exception.BizException;

@Component
@RequiredArgsConstructor
public class ConversationSocketTickets {
    public record Grant(String token, String audience, String actor, long expiresAt) {}
    private final ConversationSocketAccess access;
    private final Map<String, Grant> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public synchronized String issue(String bearer, String audience) {
        if (bearer == null || !bearer.startsWith("Bearer ")) throw new BizException(401, "AUTH_REQUIRED");
        String token = bearer.substring(7);
        var auth = access.authenticate(token, audience);
        long now = Instant.now().toEpochMilli();
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        if (tickets.size() >= 10000) throw new BizException(429, "SOCKET_CAPACITY");
        if (tickets.values().stream().filter(g -> g.actor().equals(audience + ":" + auth.getName())).count() >= 8)
            throw new BizException(429, "SOCKET_TICKET_RATE_LIMIT");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, new Grant(token, audience, audience + ":" + auth.getName(), now + 30000));
        return ticket;
    }

    public Grant consume(String ticket) {
        Grant grant = ticket == null ? null : tickets.remove(ticket);
        if (grant == null || grant.expiresAt() < Instant.now().toEpochMilli()) throw new BizException(401, "SOCKET_TICKET_EXPIRED");
        return grant;
    }
}
