package ffdd.gateway.ratelimit;

public record GatewayRateLimitKey(
        String identity,
        String routeGroup,
        int permits,
        long windowMillis) {
}
