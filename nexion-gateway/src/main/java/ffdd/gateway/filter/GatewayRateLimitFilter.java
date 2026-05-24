package ffdd.gateway.filter;

import ffdd.common.security.JwtTokenProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import ffdd.gateway.ratelimit.GatewayRateLimitDecision;
import ffdd.gateway.ratelimit.GatewayRateLimitKey;
import ffdd.gateway.ratelimit.GatewayRateLimiter;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {
    private static final String API_PREFIX = "/api/";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final long MIN_WINDOW_SECONDS = 1;
    private static final long MAX_WINDOW_SECONDS = 3600;

    private final GatewayRateLimiter rateLimiter;
    private final JwtTokenProvider tokenProvider;
    private final boolean enabled;
    private final int anonymousPermits;
    private final int userPermits;
    private final long windowMillis;

    public GatewayRateLimitFilter(
            GatewayRateLimiter rateLimiter,
            JwtTokenProvider tokenProvider,
            @Value("${nexion.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${nexion.gateway.rate-limit.anonymous-permits-per-minute:20}") int anonymousPermits,
            @Value("${nexion.gateway.rate-limit.user-permits-per-minute:120}") int userPermits,
            @Value("${nexion.gateway.rate-limit.window-seconds:60}") long windowSeconds) {
        this.rateLimiter = rateLimiter;
        this.tokenProvider = tokenProvider;
        this.enabled = enabled;
        this.anonymousPermits = Math.max(1, anonymousPermits);
        this.userPermits = Math.max(1, userPermits);
        long normalizedWindowSeconds = Math.max(MIN_WINDOW_SECONDS, Math.min(windowSeconds, MAX_WINDOW_SECONDS));
        this.windowMillis = normalizedWindowSeconds * 1000;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!enabled || !path.startsWith(API_PREFIX)) {
            return chain.filter(exchange);
        }

        RateKey rateKey = rateKey(exchange.getRequest(), path);
        GatewayRateLimitKey limiterKey = new GatewayRateLimitKey(
                rateKey.identity(), rateKey.routeGroup(), rateKey.permits(), windowMillis);
        return rateLimiter.tryAcquire(limiterKey)
                .flatMap(decision -> {
                    addLimitHeaders(exchange, decision);
                    if (!decision.allowed()) {
                        return tooManyRequests(exchange, decision);
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private RateKey rateKey(ServerHttpRequest request, String path) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length());
            String userIdentity = authenticatedUserIdentity(token);
            if (userIdentity != null) {
                return new RateKey(userIdentity, routeGroup(path), userPermits);
            }
        }
        return new RateKey("anon:" + clientIp(request), routeGroup(path), anonymousPermits);
    }

    private String routeGroup(String path) {
        String remaining = path.substring(API_PREFIX.length());
        int slash = remaining.indexOf('/');
        String routeGroup = slash < 0 ? remaining : remaining.substring(0, slash);
        return StringUtils.hasText(routeGroup) ? routeGroup : "unknown";
    }

    private String clientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            int comma = forwardedFor.indexOf(',');
            String clientIp = comma < 0 ? forwardedFor.trim() : forwardedFor.substring(0, comma).trim();
            return StringUtils.hasText(clientIp) ? clientIp : "unknown";
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private String authenticatedUserIdentity(String token) {
        try {
            Claims claims = tokenProvider.parse(token);
            if (!StringUtils.hasText(claims.getSubject())) {
                return null;
            }
            return "user:" + hash(claims.getSubject());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void addLimitHeaders(ServerWebExchange exchange, GatewayRateLimitDecision decision) {
        exchange.getResponse().getHeaders().set("X-RateLimit-Limit", Integer.toString(decision.limit()));
        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        exchange.getResponse().getHeaders().set("X-RateLimit-Backend", decision.backend());
        if (!decision.allowed()) {
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        }
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, GatewayRateLimitDecision decision) {
        byte[] body = "{\"code\":429,\"message\":\"too many requests\",\"data\":null}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory()
                .wrap(body)));
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record RateKey(String identity, String routeGroup, int permits) {
    }
}
