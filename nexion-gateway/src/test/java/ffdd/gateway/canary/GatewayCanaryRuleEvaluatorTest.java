package ffdd.gateway.canary;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.common.security.AuthHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class GatewayCanaryRuleEvaluatorTest {
    @Test
    void forceHeaderMatchesConfiguredRoute() {
        GatewayCanaryProperties properties = enabledProperties();
        GatewayCanaryProperties.RouteRule rule = route("http://localhost:18104");
        properties.getRoutes().put("commerce", rule);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/commerce/products")
                .header("X-Nexion-Canary", "true")
                .build();

        GatewayCanaryDecision decision = new GatewayCanaryRuleEvaluator(properties).evaluate("commerce", request);

        assertThat(decision.matched()).isTrue();
        assertThat(decision.reason()).isEqualTo("header");
        assertThat(decision.canaryUri().toString()).isEqualTo("http://localhost:18104");
    }

    @Test
    void appVersionMatchesConfiguredRouteVersions() {
        GatewayCanaryProperties properties = enabledProperties();
        GatewayCanaryProperties.RouteRule rule = route("http://localhost:18104");
        rule.setVersions(java.util.List.of("2.0.0", "2.1.0"));
        properties.getRoutes().put("commerce", rule);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/commerce/products")
                .header("X-App-Version", "2.1.0")
                .build();

        GatewayCanaryDecision decision = new GatewayCanaryRuleEvaluator(properties).evaluate("commerce", request);

        assertThat(decision.matched()).isTrue();
        assertThat(decision.reason()).isEqualTo("version");
    }

    @Test
    void percentRuleUsesStableUserIdentityBucket() {
        GatewayCanaryProperties properties = enabledProperties();
        GatewayCanaryProperties.RouteRule rule = route("http://localhost:18104");
        rule.setPercent(100);
        properties.getRoutes().put("commerce", rule);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/commerce/products")
                .header(AuthHeaders.SUBJECT_ID, "10001")
                .build();

        GatewayCanaryRuleEvaluator evaluator = new GatewayCanaryRuleEvaluator(properties);

        assertThat(evaluator.evaluate("commerce", request).matched()).isTrue();
        assertThat(evaluator.evaluate("commerce", request).reason()).isEqualTo("percent");

        rule.setPercent(0);
        assertThat(evaluator.evaluate("commerce", request).matched()).isFalse();
    }

    @Test
    void disabledGatewayOrRouteDoesNotMatch() {
        GatewayCanaryProperties properties = enabledProperties();
        GatewayCanaryProperties.RouteRule rule = route("http://localhost:18104");
        properties.getRoutes().put("commerce", rule);
        properties.setEnabled(false);

        GatewayCanaryDecision decision = new GatewayCanaryRuleEvaluator(properties)
                .evaluate("commerce", MockServerHttpRequest.get("/api/commerce/products").build());

        assertThat(decision.matched()).isFalse();

        properties.setEnabled(true);
        rule.setEnabled(false);
        decision = new GatewayCanaryRuleEvaluator(properties)
                .evaluate("commerce", MockServerHttpRequest.get("/api/commerce/products").build());

        assertThat(decision.matched()).isFalse();
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
