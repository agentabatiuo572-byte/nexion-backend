package ffdd.opsconsole.shared.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AdminSessionRegistry {
    private static final String SESSION_KEY_PREFIX = "ops:admin:session:";
    private static final String ADMIN_INDEX_PREFIX = "ops:admin:sessions:";

    private final StringRedisTemplate redisTemplate;

    // Check and explicit activity share one atomic boundary. Reads never extend either TTL.
    private static final DefaultRedisScript<Long> SESSION_ACTIVITY = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'adminId') ~= ARGV[1]
                or not redis.call('HGET', KEYS[1], 'issuedAt')
                or redis.call('SISMEMBER', KEYS[2], ARGV[2]) ~= 1 then return 0 end
            local rawSeen = redis.call('HGET', KEYS[1], 'lastSeenMillis')
            local seen = tonumber(rawSeen)
            if rawSeen and not seen then return 0 end
            if not seen then
                if redis.call('HGET', KEYS[1], 'lastSeenAt') ~= ARGV[5] then return 0 end
                seen = tonumber(ARGV[6])
            end
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            local ttl = tonumber(ARGV[4])
            if not seen or seen > now then return 0 end
            if now - seen >= ttl * 1000 then
                redis.call('DEL', KEYS[1])
                redis.call('SREM', KEYS[2], ARGV[2])
                return 0
            end
            if ARGV[8] == '1' then
                redis.call('HSET', KEYS[1], 'lastSeenAt', ARGV[7], 'lastSeenMillis', string.format('%.0f', now))
                redis.call('EXPIRE', KEYS[1], ttl)
                redis.call('EXPIRE', KEYS[2], ttl + 300)
            end
            return 1
            """, Long.class);

    public String createSession(Long adminId, String username) {
        return createSession(adminId, username, "unknown", "unknown");
    }

    public String createSession(Long adminId, String username, String ipAddress, String userAgent) {
        if (adminId == null || !StringUtils.hasText(username)) {
            throw new IllegalArgumentException("admin session identity required");
        }
        String sessionId = UUID.randomUUID().toString();
        AdminSessionPolicy policy = sessionPolicy();
        Duration ttl = Duration.ofMinutes(policy.idleMinutes());
        String sessionKey = sessionKey(sessionId);
        Instant issuedAt = Instant.now();
        String now = issuedAt.toString();
        redisTemplate.opsForHash().putAll(sessionKey, Map.of(
                "adminId", String.valueOf(adminId),
                "username", username.trim(),
                "issuedAt", now,
                "lastSeenAt", now,
                "lastSeenMillis", String.valueOf(issuedAt.toEpochMilli()),
                "ipAddress", safe(ipAddress, "unknown"),
                "userAgent", safe(userAgent, "unknown")));
        redisTemplate.expire(sessionKey, ttl);
        String indexKey = indexKey(adminId);
        redisTemplate.opsForSet().add(indexKey, sessionId);
        redisTemplate.expire(indexKey, ttl.plusMinutes(5));
        return sessionId;
    }

    public boolean isSessionActive(Long adminId, String sessionId) {
        return evaluateSession(adminId, sessionId, false);
    }

    public boolean recordActivity(Long adminId, String sessionId) {
        return evaluateSession(adminId, sessionId, true);
    }

    private boolean evaluateSession(Long adminId, String sessionId, boolean touch) {
        if (adminId == null || adminId <= 0 || !StringUtils.hasText(sessionId)) return false;
        String normalized = sessionId.trim();
        String key = sessionKey(normalized);
        List<Object> values = redisTemplate.opsForHash().multiGet(key, List.of("adminId", "issuedAt", "lastSeenAt"));
        if (values == null || values.size() < 3 || !String.valueOf(adminId).equals(values.get(0))) return false;
        final Instant lastSeen;
        try {
            Instant.parse(String.valueOf(values.get(1)));
            lastSeen = Instant.parse(String.valueOf(values.get(2)));
        } catch (RuntimeException invalidMetadata) {
            return false;
        }
        Instant now = Instant.now();
        Long active = redisTemplate.execute(SESSION_ACTIVITY, List.of(key, indexKey(adminId)),
                String.valueOf(adminId), normalized, String.valueOf(now.toEpochMilli()),
                String.valueOf(Duration.ofMinutes(AdminSessionPolicy.IDLE_MINUTES).toSeconds()),
                String.valueOf(values.get(2)), String.valueOf(lastSeen.toEpochMilli()), now.toString(), touch ? "1" : "0");
        return Long.valueOf(1).equals(active);
    }

    public int countActiveSessions(Long adminId) {
        if (adminId == null) {
            return 0;
        }
        Set<String> sessionIds = redisTemplate.opsForSet().members(indexKey(adminId));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        List<String> stale = new ArrayList<>();
        int active = 0;
        for (String sessionId : sessionIds) {
            if (evaluateSession(adminId, sessionId, false)) {
                active++;
            } else {
                stale.add(sessionId);
            }
        }
        pruneIndex(adminId, stale);
        return active;
    }

    public int revokeSessions(Long adminId) {
        if (adminId == null) {
            return 0;
        }
        Set<String> sessionIds = redisTemplate.opsForSet().members(indexKey(adminId));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        List<String> keys = sessionIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(AdminSessionRegistry::sessionKey)
                .toList();
        int active = 0;
        for (String sessionId : sessionIds) {
            if (evaluateSession(adminId, sessionId, false)) {
                active++;
            }
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(indexKey(adminId));
        return active;
    }

    public int revokeSessionsExcept(Long adminId, String retainedSessionId) {
        if (adminId == null) {
            return 0;
        }
        if (!StringUtils.hasText(retainedSessionId)) {
            return revokeSessions(adminId);
        }
        Set<String> sessionIds = redisTemplate.opsForSet().members(indexKey(adminId));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        String retained = retainedSessionId.trim();
        List<String> revokedIds = sessionIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(sessionId -> !sessionId.equals(retained))
                .toList();
        int active = 0;
        for (String sessionId : revokedIds) {
            if (evaluateSession(adminId, sessionId, false)) {
                active++;
            }
        }
        List<String> keys = revokedIds.stream()
                .map(AdminSessionRegistry::sessionKey)
                .toList();
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
            redisTemplate.opsForSet().remove(indexKey(adminId), revokedIds.toArray());
        }
        pruneIndex(adminId, sessionIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(sessionId -> !retained.equals(sessionId) && !revokedIds.contains(sessionId))
                .toList());
        return active;
    }

    public int revokeSession(Long adminId, String sessionId) {
        if (adminId == null || !StringUtils.hasText(sessionId)) {
            return 0;
        }
        String normalized = sessionId.trim();
        boolean active = evaluateSession(adminId, normalized, false);
        redisTemplate.delete(sessionKey(normalized));
        redisTemplate.opsForSet().remove(indexKey(adminId), normalized);
        return active ? 1 : 0;
    }

    public List<SessionView> activeSessions(Long adminId) {
        if (adminId == null) {
            return List.of();
        }
        Set<String> ids = redisTemplate.opsForSet().members(indexKey(adminId));
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<SessionView> sessions = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (String id : ids) {
            if (!evaluateSession(adminId, id, false)) {
                stale.add(id);
                continue;
            }
            Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(id));
            sessions.add(new SessionView(
                    id,
                    safe(values.get("ipAddress"), "unknown"),
                    deviceLabel(safe(values.get("userAgent"), "unknown")),
                    safe(values.get("issuedAt"), ""),
                    safe(values.get("lastSeenAt"), safe(values.get("issuedAt"), ""))));
        }
        pruneIndex(adminId, stale);
        return sessions.stream().sorted(Comparator.comparing(SessionView::lastSeenAt).reversed()).toList();
    }

    private void pruneIndex(Long adminId, List<String> stale) {
        if (!stale.isEmpty()) {
            redisTemplate.opsForSet().remove(indexKey(adminId), stale.toArray());
        }
    }

    private AdminSessionPolicy sessionPolicy() {
        // Runtime policy cannot be shortened by a still-unmigrated legacy 30min/8h row.
        return new AdminSessionPolicy(AdminSessionPolicy.IDLE_MINUTES, 0);
    }

    private String safe(Object value, String fallback) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value).trim();
    }

    private String deviceLabel(String userAgent) {
        String normalized = userAgent.toLowerCase();
        String browser = normalized.contains("edg/") ? "Edge"
                : normalized.contains("chrome/") ? "Chrome"
                : normalized.contains("firefox/") ? "Firefox"
                : normalized.contains("safari/") ? "Safari" : "Unknown browser";
        String os = normalized.contains("windows") ? "Windows"
                : normalized.contains("mac os") ? "macOS"
                : normalized.contains("android") ? "Android"
                : normalized.contains("iphone") || normalized.contains("ipad") ? "iOS" : "Unknown OS";
        return browser + " / " + os;
    }

    public record SessionView(String sessionId, String ipAddress, String device, String issuedAt, String lastSeenAt) {}

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private static String indexKey(Long adminId) {
        return ADMIN_INDEX_PREFIX + adminId;
    }
}
