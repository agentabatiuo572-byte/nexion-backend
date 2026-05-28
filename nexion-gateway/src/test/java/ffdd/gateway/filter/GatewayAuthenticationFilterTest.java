package ffdd.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.common.security.JwtTokenProvider;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayAuthenticationFilterTest {
    private final GatewayAuthenticationFilter filter = new GatewayAuthenticationFilter(
            new JwtTokenProvider("gateway-auth-test-secret-32", 60),
            "gateway-test-secret");

    @Test
    void publicConfigRoutesBypassAuthentication() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/config/day-one").build());

        StepVerifier.create(filter.filter(exchange, ignored -> {
                    chainCalled.set(true);
                    return Mono.empty();
                }))
                .verifyComplete();

        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void protectedRoutesRequireBearerToken() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/wallet/ops/stats").build());

        StepVerifier.create(filter.filter(exchange, ignored -> {
                    chainCalled.set(true);
                    return Mono.empty();
                }))
                .verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
