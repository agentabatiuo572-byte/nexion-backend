package ffdd.opsconsole.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AdminLoginGuard {
    private static final String PREFIX = "ops:admin:login-guard:";
    private final StringRedisTemplate redisTemplate;
    private final AdminLoginLockPolicy policy;

    public boolean locked(String username) {
        String value = redisTemplate.opsForValue().get(key("lock", username));
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            return Long.parseLong(value) > System.currentTimeMillis();
        } catch (NumberFormatException ex) {
            redisTemplate.delete(key("lock", username));
            return false;
        }
    }

    public void recordFailure(String username) {
        String shortKey = key("short", username);
        String dailyKey = key("daily", username);
        Long shortFailures = redisTemplate.opsForValue().increment(shortKey);
        Long dailyFailures = redisTemplate.opsForValue().increment(dailyKey);
        if (Long.valueOf(1).equals(shortFailures)) {
            redisTemplate.expire(shortKey, Duration.ofMinutes(15));
        }
        if (Long.valueOf(1).equals(dailyFailures)) {
            redisTemplate.expire(dailyKey, Duration.ofHours(24));
        }
        policy.lockDuration(value(shortFailures), value(dailyFailures)).ifPresent(duration -> {
            String lockKey = key("lock", username);
            redisTemplate.opsForValue().set(lockKey, String.valueOf(System.currentTimeMillis() + duration.toMillis()), duration);
        });
    }

    public void recordSuccess(String username) {
        redisTemplate.delete(List.of(key("short", username), key("daily", username), key("lock", username)));
    }

    private long value(Long count) {
        return count == null ? 0 : count;
    }

    private String key(String window, String username) {
        String normalized = StringUtils.hasText(username) ? username.trim().toLowerCase(Locale.ROOT) : "";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return PREFIX + window + ":" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
