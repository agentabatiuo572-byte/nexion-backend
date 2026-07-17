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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ffdd.opsconsole.platform.infrastructure.AdminSecurityBaselineEntity;
import ffdd.opsconsole.platform.mapper.AdminSecurityBaselineMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AdminSessionRegistry {
    private static final String SESSION_KEY_PREFIX = "ops:admin:session:";
    private static final String ADMIN_INDEX_PREFIX = "ops:admin:sessions:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;
    private final AdminSecurityBaselineMapper baselineMapper;

    public String createSession(Long adminId, String username) {
        return createSession(adminId, username, "unknown", "unknown");
    }

    public String createSession(Long adminId, String username, String ipAddress, String userAgent) {
        if (adminId == null || !StringUtils.hasText(username)) {
            throw new IllegalArgumentException("admin session identity required");
        }
        String sessionId = UUID.randomUUID().toString();
        AdminSessionPolicy policy = sessionPolicy();
        Duration ttl = Duration.ofMinutes(policy.absoluteMinutes());
        String sessionKey = sessionKey(sessionId);
        String now = Instant.now().toString();
        redisTemplate.opsForHash().putAll(sessionKey, Map.of(
                "adminId", String.valueOf(adminId),
                "username", username.trim(),
                "issuedAt", now,
                "lastSeenAt", now,
                "ipAddress", safe(ipAddress, "unknown"),
                "userAgent", safe(userAgent, "unknown")));
        redisTemplate.expire(sessionKey, ttl);
        String indexKey = indexKey(adminId);
        redisTemplate.opsForSet().add(indexKey, sessionId);
        redisTemplate.expire(indexKey, ttl.plusMinutes(5));
        return sessionId;
    }

    public boolean isSessionActive(Long adminId, String sessionId) {
        return evaluateSession(adminId, sessionId, true);
    }

    private boolean evaluateSession(Long adminId, String sessionId, boolean touch) {
        if (adminId == null || !StringUtils.hasText(sessionId)) {
            return false;
        }
        String normalizedSessionId = sessionId.trim();
        String key = sessionKey(normalizedSessionId);
        List<Object> values = redisTemplate.opsForHash().multiGet(key, List.of("adminId", "issuedAt", "lastSeenAt"));
        if (values == null || values.size() < 3 || !String.valueOf(adminId).equals(values.get(0))) {
            return false;
        }
        try {
            Instant issuedAt = Instant.parse(String.valueOf(values.get(1)));
            Object rawLastSeen = values.get(2);
            Instant lastSeenAt = rawLastSeen == null ? issuedAt : Instant.parse(String.valueOf(rawLastSeen));
            Instant now = Instant.now();
            AdminSessionPolicy policy = sessionPolicy();
            if (!policy.isActive(issuedAt, lastSeenAt, now)) {
                redisTemplate.delete(key);
                redisTemplate.opsForSet().remove(indexKey(adminId), normalizedSessionId);
                return false;
            }
            if (touch) {
                redisTemplate.opsForHash().put(key, "lastSeenAt", now.toString());
                Duration remaining = Duration.between(now, issuedAt.plus(Duration.ofMinutes(policy.absoluteMinutes())));
                if (!remaining.isNegative() && !remaining.isZero()) {
                    redisTemplate.expire(key, remaining);
                }
            }
            return true;
        } catch (RuntimeException ex) {
            redisTemplate.delete(key);
            redisTemplate.opsForSet().remove(indexKey(adminId), normalizedSessionId);
            return false;
        }
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
        long idle = 30;
        long absolute = Math.min(Math.max(jwtProperties.getTtlMinutes(), 1), 480);
        try {
            AdminSecurityBaselineEntity baseline = baselineMapper.selectActiveByKey("session");
            if (baseline != null && StringUtils.hasText(baseline.getBaselineValue())) {
                Matcher matcher = Pattern.compile("(\\d+)\\s*min\\s*/\\s*(\\d+)\\s*h", Pattern.CASE_INSENSITIVE)
                        .matcher(baseline.getBaselineValue());
                if (matcher.find()) {
                    idle = Long.parseLong(matcher.group(1));
                    absolute = Long.parseLong(matcher.group(2)) * 60L;
                }
            }
        } catch (RuntimeException ignored) {
            // Authentication must remain fail-closed with the seeded 30m/8h baseline.
        }
        return new AdminSessionPolicy(idle, absolute);
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
