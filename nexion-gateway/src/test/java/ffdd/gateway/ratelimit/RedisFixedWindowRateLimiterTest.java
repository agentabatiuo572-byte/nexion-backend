package ffdd.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

class RedisFixedWindowRateLimiterTest {
    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    private final ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-24T00:00:05Z"), ZoneId.of("UTC"));

    @Test
    void usesRedisCounterAndExpiresFirstHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(Mono.just(1L), Mono.just(2L), Mono.just(3L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(Boolean.TRUE));
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(redisTemplate, clock, 100);
        GatewayRateLimitKey key = new GatewayRateLimitKey("user:sensitive-token-hash", "wallet", 2, 60_000);

        GatewayRateLimitDecision first = limiter.tryAcquire(key).block();
        GatewayRateLimitDecision second = limiter.tryAcquire(key).block();
        GatewayRateLimitDecision third = limiter.tryAcquire(key).block();

        assertThat(first.allowed()).isTrue();
        assertThat(first.backend()).isEqualTo("redis");
        assertThat(second.remaining()).isZero();
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isEqualTo(55);

        ArgumentCaptor<String> redisKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(3)).increment(redisKeyCaptor.capture());
        verify(redisTemplate, times(1)).expire(redisKeyCaptor.getValue(), Duration.ofMillis(61_000));
        assertThat(redisKeyCaptor.getAllValues()).allMatch(value -> value.startsWith("nexion:gateway:rate-limit:"));
        assertThat(redisKeyCaptor.getValue()).doesNotContain("sensitive-token-hash");
    }
}
