package ffdd.gateway.ratelimit;

public record GatewayRateLimitDecision(
        boolean allowed,
        int limit,
        int remaining,
        long retryAfterSeconds,
        String backend) {
    public GatewayRateLimitDecision withBackend(String value) {
        return new GatewayRateLimitDecision(allowed, limit, remaining, retryAfterSeconds, value);
    }
}
