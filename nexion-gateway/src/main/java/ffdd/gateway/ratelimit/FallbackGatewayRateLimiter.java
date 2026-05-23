package ffdd.gateway.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Primary
@Component
public class FallbackGatewayRateLimiter implements GatewayRateLimiter {
    private final RedisFixedWindowRateLimiter redisLimiter;
    private final LocalFixedWindowRateLimiter localLimiter;
    private final boolean redisEnabled;

    public FallbackGatewayRateLimiter(
            ObjectProvider<RedisFixedWindowRateLimiter> redisLimiter,
            LocalFixedWindowRateLimiter localLimiter,
            @Value("${nexion.gateway.rate-limit.redis.enabled:true}") boolean redisEnabled) {
        this.redisLimiter = redisLimiter.getIfAvailable();
        this.localLimiter = localLimiter;
        this.redisEnabled = redisEnabled;
    }

    @Override
    public Mono<GatewayRateLimitDecision> tryAcquire(GatewayRateLimitKey key) {
        if (!redisEnabled || redisLimiter == null) {
            return localLimiter.tryAcquire(key);
        }
        return redisLimiter.tryAcquire(key)
                .onErrorResume(ignored -> localLimiter.tryAcquire(key)
                        .map(decision -> decision.withBackend("local-fallback")));
    }
}
