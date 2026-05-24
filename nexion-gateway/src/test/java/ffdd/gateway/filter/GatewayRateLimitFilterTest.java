package ffdd.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.common.security.JwtTokenProvider;
import ffdd.gateway.ratelimit.GatewayRateLimitDecision;
import ffdd.gateway.ratelimit.GatewayRateLimitKey;
import ffdd.gateway.ratelimit.GatewayRateLimiter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayRateLimitFilterTest {
    private final JwtTokenProvider tokenProvider =
            new JwtTokenProvider("gateway-rate-limit-test-secret-32", 60);

    @Test
    void validBearerUsesAuthenticatedUserQuota() {
        AtomicReference<GatewayRateLimitKey> captured = new AtomicReference<>();
        GatewayRateLimitFilter filter = filter(captured);
        String token = tokenProvider.createToken(10001L, "USER", "user", List.of("PERM_WALLET_READ"));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/wallet/users/10001")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty()))
                .verifyComplete();

        GatewayRateLimitKey key = captured.get();
        assertThat(key.identity()).startsWith("user:");
        assertThat(key.identity()).doesNotContain(token);
        assertThat(key.routeGroup()).isEqualTo("wallet");
        assertThat(key.permits()).isEqualTo(10);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Backend")).isEqualTo("test");
    }

    @Test
    void forgedBearerStaysOnAnonymousIpQuota() {
        AtomicReference<GatewayRateLimitKey> captured = new AtomicReference<>();
        GatewayRateLimitFilter filter = filter(captured);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/commerce/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer fake.token.value")
                .header("X-Forwarded-For", "203.0.113.10, 10.0.0.1")
                .build());

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty()))
                .verifyComplete();

        GatewayRateLimitKey key = captured.get();
        assertThat(key.identity()).isEqualTo("anon:203.0.113.10");
        assertThat(key.routeGroup()).isEqualTo("commerce");
        assertThat(key.permits()).isEqualTo(2);
    }

    @Test
    void skipsNonApiRoutes() {
        AtomicReference<GatewayRateLimitKey> captured = new AtomicReference<>();
        GatewayRateLimitFilter filter = filter(captured);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty()))
                .verifyComplete();

        assertThat(captured).hasValue(null);
        assertThat(exchange.getResponse().getHeaders()).doesNotContainKey("X-RateLimit-Backend");
    }

    private GatewayRateLimitFilter filter(AtomicReference<GatewayRateLimitKey> captured) {
        GatewayRateLimiter limiter = key -> {
            captured.set(key);
            return Mono.just(new GatewayRateLimitDecision(true, key.permits(), key.permits() - 1, 60, "test"));
        };
        return new GatewayRateLimitFilter(limiter, tokenProvider, true, 2, 10, 60);
    }
}
