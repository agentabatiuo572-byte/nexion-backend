package ffdd.gateway.filter;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

import ffdd.gateway.canary.GatewayCanaryDecision;
import ffdd.gateway.canary.GatewayCanaryRuleEvaluator;
import java.net.URI;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Component
public class GatewayCanaryFilter implements GlobalFilter, Ordered {
    private static final String API_PREFIX = "/api/";
    private static final int ROUTE_TO_REQUEST_URL_FILTER_ORDER = 10000;

    private final GatewayCanaryRuleEvaluator evaluator;

    public GatewayCanaryFilter(GatewayCanaryRuleEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI requestUrl = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
        if (requestUrl == null) {
            return chain.filter(exchange);
        }

        String routeGroup = routeGroup(exchange.getRequest().getURI().getPath());
        GatewayCanaryDecision decision = evaluator.evaluate(routeGroup, exchange.getRequest());
        if (!decision.matched()) {
            return chain.filter(exchange);
        }

        URI canaryRequestUrl = rewrite(requestUrl, decision.canaryUri());
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, canaryRequestUrl);
        exchange.getResponse().getHeaders().set("X-Nexion-Canary", "true");
        exchange.getResponse().getHeaders().set("X-Nexion-Canary-Route", decision.routeGroup());
        exchange.getResponse().getHeaders().set("X-Nexion-Canary-Reason", decision.reason());
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return ROUTE_TO_REQUEST_URL_FILTER_ORDER + 1;
    }

    private URI rewrite(URI requestUrl, URI canaryBaseUri) {
        return UriComponentsBuilder.fromUri(canaryBaseUri)
                .replacePath(joinPaths(canaryBaseUri.getRawPath(), requestUrl.getRawPath()))
                .replaceQuery(requestUrl.getRawQuery())
                .build(true)
                .toUri();
    }

    private String joinPaths(String basePath, String requestPath) {
        if (!StringUtils.hasText(basePath) || "/".equals(basePath)) {
            return StringUtils.hasText(requestPath) ? requestPath : "/";
        }
        if (!StringUtils.hasText(requestPath) || "/".equals(requestPath)) {
            return basePath;
        }
        String normalizedBase = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        String normalizedRequest = requestPath.startsWith("/") ? requestPath : "/" + requestPath;
        return normalizedBase + normalizedRequest;
    }

    private String routeGroup(String path) {
        if (!StringUtils.hasText(path)) {
            return "unknown";
        }
        if (path.startsWith(API_PREFIX)) {
            return firstSegment(path.substring(API_PREFIX.length()));
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return firstSegment(normalized);
    }

    private String firstSegment(String value) {
        int slash = value.indexOf('/');
        String segment = slash < 0 ? value : value.substring(0, slash);
        return StringUtils.hasText(segment) ? segment : "unknown";
    }
}
