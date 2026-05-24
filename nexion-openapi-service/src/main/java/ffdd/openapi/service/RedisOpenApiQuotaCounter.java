package ffdd.openapi.service;

import ffdd.common.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisOpenApiQuotaCounter implements OpenApiQuotaCounter {
    private static final String KEY_PREFIX = "nexion:openapi:quota:";

    private final StringRedisTemplate redisTemplate;

    public RedisOpenApiQuotaCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long incrementQps(String appKey, String apiPath) {
        return increment(key("qps", appKey, apiPath, null), Duration.ofSeconds(2));
    }

    @Override
    public long incrementDaily(String appKey, String apiPath, LocalDate date) {
        return increment(key("daily", appKey, apiPath, date.toString()), Duration.ofDays(2));
    }

    private long increment(String key, Duration ttl) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                throw new IllegalStateException("Redis INCR returned null");
            }
            if (count == 1L) {
                redisTemplate.expire(key, ttl);
            }
            return count;
        } catch (RuntimeException ex) {
            throw new BizException(503, "OpenAPI quota limiter unavailable");
        }
    }

    private String key(String bucket, String appKey, String apiPath, String suffix) {
        StringBuilder builder = new StringBuilder(KEY_PREFIX)
                .append(bucket)
                .append(':')
                .append(appKey)
                .append(':')
                .append(sha256(apiPath).substring(0, 16));
        if (suffix != null) {
            builder.append(':').append(suffix);
        }
        return builder.toString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BizException("Unable to hash OpenAPI quota key");
        }
    }
}
