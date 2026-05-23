package ffdd.gateway.ratelimit;

import reactor.core.publisher.Mono;

public interface GatewayRateLimiter {
    Mono<GatewayRateLimitDecision> tryAcquire(GatewayRateLimitKey key);
}
