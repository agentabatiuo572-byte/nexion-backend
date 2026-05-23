package ffdd.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LocalFixedWindowRateLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final LocalFixedWindowRateLimiter limiter = new LocalFixedWindowRateLimiter(clock);

    @Test
    void rejectsRequestsAfterPermitLimitInsideSameWindow() {
        GatewayRateLimitKey key = new GatewayRateLimitKey("anon:127.0.0.1", "commerce", 2, 60_000);

        GatewayRateLimitDecision first = limiter.tryAcquire(key).block();
        GatewayRateLimitDecision second = limiter.tryAcquire(key).block();
        GatewayRateLimitDecision third = limiter.tryAcquire(key).block();

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(third.allowed()).isFalse();
        assertThat(third.limit()).isEqualTo(2);
        assertThat(third.remaining()).isZero();
        assertThat(third.retryAfterSeconds()).isPositive();
    }

    @Test
    void resetsCounterWhenWindowChanges() {
        GatewayRateLimitKey key = new GatewayRateLimitKey("user:token", "wallet", 1, 1_000);

        GatewayRateLimitDecision first = limiter.tryAcquire(key).block();
        GatewayRateLimitDecision blocked = limiter.tryAcquire(key).block();
        clock.advanceMillis(1_000);
        GatewayRateLimitDecision reset = limiter.tryAcquire(key).block();

        assertThat(first.allowed()).isTrue();
        assertThat(blocked.allowed()).isFalse();
        assertThat(reset.allowed()).isTrue();
        assertThat(reset.remaining()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-05-23T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }
    }
}
