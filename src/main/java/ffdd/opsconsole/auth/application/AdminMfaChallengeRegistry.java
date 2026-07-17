package ffdd.opsconsole.auth.application;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AdminMfaChallengeRegistry {
    private static final String PREFIX = "ops:admin:mfa-challenge:";
    private static final String USED_COUNTER_PREFIX = "ops:admin:mfa-used-counter:";
    private static final Duration USED_COUNTER_TTL = Duration.ofMinutes(2);
    private final StringRedisTemplate redisTemplate;
    private final AdminMfaProperties properties;

    public String create(Long adminId, String username, String encryptedSecret, boolean enrollment) {
        String id = UUID.randomUUID().toString();
        String key = key(id);
        redisTemplate.opsForHash().putAll(key, Map.of(
                "adminId", String.valueOf(adminId),
                "username", username,
                "secret", encryptedSecret,
                "mode", enrollment ? "ENROLL" : "VERIFY",
                "attempts", "0"));
        redisTemplate.expire(key, Duration.ofSeconds(Math.max(60, properties.getChallengeTtlSeconds())));
        return id;
    }

    public Challenge read(String challengeId) {
        if (!StringUtils.hasText(challengeId)) {
            return null;
        }
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(challengeId.trim()));
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return new Challenge(
                    Long.valueOf(String.valueOf(values.get("adminId"))),
                    String.valueOf(values.get("username")),
                    String.valueOf(values.get("secret")),
                    "ENROLL".equals(values.get("mode")),
                    Integer.parseInt(String.valueOf(values.getOrDefault("attempts", "0"))));
        } catch (RuntimeException ex) {
            delete(challengeId);
            return null;
        }
    }

    /** Atomically makes a verified password challenge single-use. */
    public Challenge consume(String challengeId) {
        Challenge challenge = read(challengeId);
        if (challenge == null) {
            return null;
        }
        Boolean removed = redisTemplate.delete(key(challengeId.trim()));
        return Boolean.TRUE.equals(removed) ? challenge : null;
    }

    /** Atomically prevents a valid TOTP timestep being accepted twice for the same admin. */
    public boolean claimTotpCounter(Long adminId, long counter) {
        if (adminId == null) {
            return false;
        }
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                USED_COUNTER_PREFIX + adminId + ":" + counter,
                "1",
                USED_COUNTER_TTL);
        return Boolean.TRUE.equals(claimed);
    }

    public int recordFailure(String challengeId) {
        Long attempts = redisTemplate.opsForHash().increment(key(challengeId), "attempts", 1);
        int count = attempts == null ? properties.getChallengeMaxAttempts() : attempts.intValue();
        if (count >= properties.getChallengeMaxAttempts()) {
            delete(challengeId);
        }
        return count;
    }

    public void delete(String challengeId) {
        if (StringUtils.hasText(challengeId)) {
            redisTemplate.delete(key(challengeId.trim()));
        }
    }

    private String key(String id) {
        return PREFIX + id;
    }

    public record Challenge(Long adminId, String username, String encryptedSecret, boolean enrollment, int attempts) {}
}
