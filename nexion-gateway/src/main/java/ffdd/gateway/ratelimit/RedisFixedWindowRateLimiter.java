package ffdd.gateway.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisFixedWindowRateLimiter implements GatewayRateLimiter {
    private static final String KEY_PREFIX = "nexion:gateway:rate-limit:";
    private static final long MIN_TIMEOUT_MS = 10;
    private static final long MAX_TIMEOUT_MS = 1000;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Clock clock;
    private final Duration timeout;

    public RedisFixedWindowRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            Clock clock,
            @Value("${nexion.gateway.rate-limit.redis.timeout-ms:50}") long timeoutMs) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.timeout = Duration.ofMillis(Math.max(MIN_TIMEOUT_MS, Math.min(timeoutMs, MAX_TIMEOUT_MS)));
    }

    @Override
    public Mono<GatewayRateLimitDecision> tryAcquire(GatewayRateLimitKey key) {
        long now = clock.millis();
        long windowStart = LocalFixedWindowRateLimiter.windowStart(now, key.windowMillis());
        String redisKey = redisKey(key, windowStart);
        return redisTemplate.opsForValue().increment(redisKey)
                .flatMap(count -> expireFirstHit(redisKey, key.windowMillis(), count)
                        .thenReturn(LocalFixedWindowRateLimiter
                                .decision(key, count, now, windowStart)
                                .withBackend("redis")))
                .timeout(timeout);
    }

    private Mono<Boolean> expireFirstHit(String redisKey, long windowMillis, Long count) {
        if (count == null || count != 1L) {
            return Mono.just(Boolean.TRUE);
        }
        return redisTemplate.expire(redisKey, Duration.ofMillis(windowMillis + 1000));
    }

    private String redisKey(GatewayRateLimitKey key, long windowStart) {
        return KEY_PREFIX + hash(key.identity() + ":" + key.routeGroup()) + ":" + windowStart;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
