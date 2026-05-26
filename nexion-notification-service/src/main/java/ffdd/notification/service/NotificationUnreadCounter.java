package ffdd.notification.service;

import ffdd.common.exception.BizException;
import ffdd.notification.dto.NotificationUnreadCountResponse;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NotificationUnreadCounter {
    private static final String KEY_PREFIX = "notification:unread:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public NotificationUnreadCounter(
            StringRedisTemplate redisTemplate,
            @Value("${nexion.notification.unread.ttl-seconds:60}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(Math.max(5, ttlSeconds));
    }

    public NotificationUnreadCountResponse count(Long userId, LongSupplier databaseCounter) {
        requireUserId(userId);
        String key = key(userId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(cached)) {
                return new NotificationUnreadCountResponse(userId, Long.parseLong(cached), "HIT");
            }
            long unreadCount = Math.max(0, databaseCounter.getAsLong());
            redisTemplate.opsForValue().set(key, Long.toString(unreadCount), ttl);
            return new NotificationUnreadCountResponse(userId, unreadCount, "MISS");
        } catch (RuntimeException ex) {
            return new NotificationUnreadCountResponse(userId, Math.max(0, databaseCounter.getAsLong()), "FALLBACK");
        }
    }

    public void increment(Long userId) {
        if (userId == null || userId < 1) {
            return;
        }
        try {
            Long value = redisTemplate.opsForValue().increment(key(userId));
            if (value == null || value <= 1L) {
                redisTemplate.expire(key(userId), ttl);
            }
        } catch (RuntimeException ignored) {
            // Notification rows are durable; Redis unread counters are an acceleration path.
        }
    }

    public void invalidate(Long userId) {
        if (userId == null || userId < 1) {
            return;
        }
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException ignored) {
            // The next unread-count request can rebuild the value from MySQL.
        }
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new BizException("User id is required");
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
