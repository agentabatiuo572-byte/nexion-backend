package ffdd.opsconsole.shared.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AdminSessionRegistry {
    private static final String SESSION_KEY_PREFIX = "ops:admin:session:";
    private static final String ADMIN_INDEX_PREFIX = "ops:admin:sessions:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    public String createSession(Long adminId, String username) {
        if (adminId == null || !StringUtils.hasText(username)) {
            throw new IllegalArgumentException("admin session identity required");
        }
        String sessionId = UUID.randomUUID().toString();
        Duration ttl = sessionTtl();
        String sessionKey = sessionKey(sessionId);
        redisTemplate.opsForHash().putAll(sessionKey, Map.of(
                "adminId", String.valueOf(adminId),
                "username", username.trim(),
                "issuedAt", Instant.now().toString()));
        redisTemplate.expire(sessionKey, ttl);
        String indexKey = indexKey(adminId);
        redisTemplate.opsForSet().add(indexKey, sessionId);
        redisTemplate.expire(indexKey, ttl.plusMinutes(5));
        return sessionId;
    }

    public boolean isSessionActive(Long adminId, String sessionId) {
        if (adminId == null || !StringUtils.hasText(sessionId)) {
            return false;
        }
        Object storedAdminId = redisTemplate.opsForHash().get(sessionKey(sessionId.trim()), "adminId");
        return String.valueOf(adminId).equals(storedAdminId);
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
            if (isSessionActive(adminId, sessionId)) {
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
            if (isSessionActive(adminId, sessionId)) {
                active++;
            }
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(indexKey(adminId));
        return active;
    }

    private void pruneIndex(Long adminId, List<String> stale) {
        if (!stale.isEmpty()) {
            redisTemplate.opsForSet().remove(indexKey(adminId), stale.toArray());
        }
    }

    private Duration sessionTtl() {
        return Duration.ofMinutes(Math.max(jwtProperties.getTtlMinutes(), 1));
    }

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private static String indexKey(Long adminId) {
        return ADMIN_INDEX_PREFIX + adminId;
    }
}
