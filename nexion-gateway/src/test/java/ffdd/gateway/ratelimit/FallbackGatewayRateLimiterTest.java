package ffdd.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

class FallbackGatewayRateLimiterTest {
    @Test
    void usesLocalLimiterWhenRedisLimiterFails() {
        RedisFixedWindowRateLimiter redisLimiter = mock(RedisFixedWindowRateLimiter.class);
        when(redisLimiter.tryAcquire(any())).thenReturn(Mono.error(new IllegalStateException("redis down")));

        FallbackGatewayRateLimiter limiter = new FallbackGatewayRateLimiter(
                provider(redisLimiter),
                new LocalFixedWindowRateLimiter(Clock.fixed(
                        Instant.parse("2026-05-23T00:00:00Z"),
                        ZoneId.of("UTC"))),
                true);

        GatewayRateLimitDecision decision = limiter.tryAcquire(
                new GatewayRateLimitKey("anon:127.0.0.1", "commerce", 1, 60_000)).block();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.backend()).isEqualTo("local-fallback");
    }

    @Test
    void localFallbackStillBlocksAfterLocalLimit() {
        RedisFixedWindowRateLimiter redisLimiter = mock(RedisFixedWindowRateLimiter.class);
        when(redisLimiter.tryAcquire(any())).thenReturn(Mono.error(new IllegalStateException("redis timeout")));

        FallbackGatewayRateLimiter limiter = new FallbackGatewayRateLimiter(
                provider(redisLimiter),
                new LocalFixedWindowRateLimiter(Clock.fixed(
                        Instant.parse("2026-05-23T00:00:00Z"),
                        ZoneId.of("UTC"))),
                true);
        GatewayRateLimitKey key = new GatewayRateLimitKey("anon:127.0.0.1", "commerce", 1, 60_000);

        GatewayRateLimitDecision first = limiter.tryAcquire(key).block();
        GatewayRateLimitDecision second = limiter.tryAcquire(key).block();

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isFalse();
        assertThat(second.backend()).isEqualTo("local-fallback");
    }

    @Test
    void usesLocalLimiterWhenRedisIsDisabled() {
        RedisFixedWindowRateLimiter redisLimiter = mock(RedisFixedWindowRateLimiter.class);
        FallbackGatewayRateLimiter limiter = new FallbackGatewayRateLimiter(
                provider(redisLimiter),
                new LocalFixedWindowRateLimiter(Clock.fixed(
                        Instant.parse("2026-05-23T00:00:00Z"),
                        ZoneId.of("UTC"))),
                false);

        GatewayRateLimitDecision decision = limiter.tryAcquire(
                new GatewayRateLimitKey("anon:127.0.0.1", "commerce", 1, 60_000)).block();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.backend()).isEqualTo("local");
    }

    private static ObjectProvider<RedisFixedWindowRateLimiter> provider(RedisFixedWindowRateLimiter limiter) {
        return new ObjectProvider<>() {
            @Override
            public RedisFixedWindowRateLimiter getObject(Object... args) {
                return limiter;
            }

            @Override
            public RedisFixedWindowRateLimiter getIfAvailable() {
                return limiter;
            }

            @Override
            public RedisFixedWindowRateLimiter getIfUnique() {
                return limiter;
            }

            @Override
            public RedisFixedWindowRateLimiter getObject() {
                return limiter;
            }
        };
    }
}
