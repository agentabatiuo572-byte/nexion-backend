package ffdd.gateway.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class LocalFixedWindowRateLimiter implements GatewayRateLimiter {
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong nextCleanupAt = new AtomicLong(0);
    private final Clock clock;

    public LocalFixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<GatewayRateLimitDecision> tryAcquire(GatewayRateLimitKey key) {
        long now = clock.millis();
        long windowStart = windowStart(now, key.windowMillis());
        String counterKey = key.identity() + ":" + key.routeGroup();
        WindowCounter counter = counters.compute(counterKey, (ignored, current) -> {
            if (current == null || current.windowStart() != windowStart) {
                return new WindowCounter(windowStart, 1);
            }
            current.increment();
            return current;
        });
        cleanupIfDue(now, windowStart, key.windowMillis());
        int count = counter.count();
        return Mono.just(decision(key, count, now, windowStart).withBackend("local"));
    }

    static GatewayRateLimitDecision decision(GatewayRateLimitKey key, long count, long now, long windowStart) {
        int remaining = (int) Math.max(0, key.permits() - count);
        long retryAfterSeconds = Math.max(1, ((windowStart + key.windowMillis()) - now + 999) / 1000);
        return new GatewayRateLimitDecision(
                count <= key.permits(),
                key.permits(),
                remaining,
                retryAfterSeconds,
                "local");
    }

    static long windowStart(long now, long windowMillis) {
        return (now / windowMillis) * windowMillis;
    }

    private void cleanupIfDue(long now, long currentWindowStart, long windowMillis) {
        long cleanupAt = nextCleanupAt.get();
        if (now < cleanupAt || !nextCleanupAt.compareAndSet(cleanupAt, now + windowMillis)) {
            return;
        }
        long oldestWindowToKeep = currentWindowStart - windowMillis;
        counters.entrySet().removeIf(entry -> entry.getValue().windowStart() < oldestWindowToKeep);
    }

    private static final class WindowCounter {
        private final long windowStart;
        private int count;

        private WindowCounter(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }

        private long windowStart() {
            return windowStart;
        }

        private int count() {
            return count;
        }

        private void increment() {
            count++;
        }
    }
}
