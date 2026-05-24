package ffdd.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

import ffdd.gateway.canary.GatewayCanaryProperties;
import ffdd.gateway.canary.GatewayCanaryRuleEvaluator;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewayCanaryFilterTest {
    @Test
    void rewritesResolvedRequestUrlWhenCanaryMatches() {
        GatewayCanaryProperties properties = enabledProperties();
        properties.getRoutes().put("commerce", route("http://localhost:18104"));
        GatewayCanaryFilter filter = new GatewayCanaryFilter(new GatewayCanaryRuleEvaluator(properties));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/commerce/products?pageNum=1")
                .header("X-Nexion-Canary", "true")
                .build());
        exchange.getAttributes().put(
                GATEWAY_REQUEST_URL_ATTR,
                URI.create("http://localhost:8104/commerce/products?pageNum=1"));

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty()))
                .verifyComplete();

        assertThat((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://localhost:18104/commerce/products?pageNum=1"));
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Nexion-Canary")).isEqualTo("true");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Nexion-Canary-Route")).isEqualTo("commerce");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Nexion-Canary-Reason")).isEqualTo("header");
    }

    @Test
    void leavesResolvedRequestUrlUnchangedWhenCanaryDoesNotMatch() {
        GatewayCanaryProperties properties = enabledProperties();
        GatewayCanaryProperties.RouteRule rule = route("http://localhost:18104");
        rule.setPercent(0);
        properties.getRoutes().put("commerce", rule);
        GatewayCanaryFilter filter = new GatewayCanaryFilter(new GatewayCanaryRuleEvaluator(properties));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/commerce/products").build());
        URI stableUri = URI.create("http://localhost:8104/commerce/products");
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, stableUri);

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty()))
                .verifyComplete();

        assertThat((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).isEqualTo(stableUri);
        assertThat(exchange.getResponse().getHeaders()).doesNotContainKey("X-Nexion-Canary");
    }

    private GatewayCanaryProperties enabledProperties() {
        GatewayCanaryProperties properties = new GatewayCanaryProperties();
        properties.setEnabled(true);
        return properties;
    }

    private GatewayCanaryProperties.RouteRule route(String canaryUri) {
        GatewayCanaryProperties.RouteRule rule = new GatewayCanaryProperties.RouteRule();
        rule.setEnabled(true);
        rule.setCanaryUri(canaryUri);
        return rule;
    }
}
