package ffdd.opsconsole.content.realtime;

import ffdd.opsconsole.shared.exception.BizException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConversationSocketTickets {
    private static final String PREFIX = "nexion:{conversation-socket-ticket-v1}:";
    private static final String OUTSTANDING = PREFIX + "outstanding";
    private static final String ACTOR_PREFIX = PREFIX + "actor:";
    private static final String TTL_MILLIS = "30000";
    private static final DefaultRedisScript<Long> ISSUE = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
            if redis.call('ZCARD', KEYS[2]) >= 10000 then return -1 end
            if redis.call('ZCARD', KEYS[3]) >= 8 then return -2 end
            if not redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3], 'NX') then return 0 end
            local expires = now + tonumber(ARGV[3])
            redis.call('ZADD', KEYS[2], expires, ARGV[1])
            redis.call('ZADD', KEYS[3], expires, ARGV[1])
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[3], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return nil end
            local actor = string.sub(value, 1, string.find(value, string.char(10), 1, true) - 1)
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('ZREM', ARGV[2] .. actor, ARGV[1])
            return value
            """, String.class);

    public record Grant(String token, String audience, String actor) {}

    private final ConversationSocketAccess access;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public String issue(String bearer, String audience) {
        if (bearer == null || !bearer.startsWith("Bearer ")) throw new BizException(401, "AUTH_REQUIRED");
        String token = bearer.substring(7);
        var auth = access.revalidate(token, audience);
        String actor = audience + ":" + auth.getName();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String id = digest(ticket);
        // The opaque ticket supplies the encryption key. Redis never sees it or the bearer.
        String encrypted = Jwts.builder().claim("token", token).claim("audience", audience).claim("actor", actor)
                .encryptWith(new SecretKeySpec(secret, "AES"), Jwts.ENC.A256GCM).compact();
        Long result = execute(ISSUE, List.of(PREFIX + id, OUTSTANDING, ACTOR_PREFIX + actor),
                id, actor + "\n" + encrypted, TTL_MILLIS);
        if (Long.valueOf(-1).equals(result)) throw new BizException(429, "SOCKET_CAPACITY");
        if (Long.valueOf(-2).equals(result)) throw new BizException(429, "SOCKET_TICKET_RATE_LIMIT");
        if (!Long.valueOf(1).equals(result)) throw unavailable();
        return ticket;
    }

    public Grant consume(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw new BizException(401, "SOCKET_TICKET_EXPIRED");
        String id = digest(ticket);
        String value = execute(CONSUME, List.of(PREFIX + id, OUTSTANDING), id, ACTOR_PREFIX);
        if (value == null) throw new BizException(401, "SOCKET_TICKET_EXPIRED");
        try {
            var key = new SecretKeySpec(Base64.getUrlDecoder().decode(ticket), "AES");
            var grant = Jwts.parser().decryptWith(key).build()
                    .parseEncryptedClaims(value.substring(value.indexOf('\n') + 1)).getPayload();
            return new Grant(grant.get("token", String.class), grant.get("audience", String.class),
                    grant.get("actor", String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            throw unavailable();
        }
    }

    private <T> T execute(DefaultRedisScript<T> script, List<String> keys, String... arguments) {
        try {
            return redis.execute(script, keys, (Object[]) arguments);
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    private static String digest(String ticket) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static BizException unavailable() {
        return new BizException(503, "SOCKET_TICKET_STORE_UNAVAILABLE");
    }
}
