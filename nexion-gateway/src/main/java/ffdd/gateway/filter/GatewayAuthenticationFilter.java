package ffdd.gateway.filter;

import ffdd.common.security.AuthHeaders;
import ffdd.common.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/admin/login",
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/users/login",
            "/api/auth/users/register/sms-code",
            "/api/auth/users/register",
            "/api/config/",
            "/api/openapi/v1/",
            "/actuator/");

    private final JwtTokenProvider tokenProvider;
    private final String gatewaySecret;

    public GatewayAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            @Value("${nexion.gateway.internal-secret:nexion-local-gateway-secret}") String gatewaySecret) {
        this.tokenProvider = tokenProvider;
        this.gatewaySecret = gatewaySecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();
        removeInternalAuthHeaders(requestBuilder);

        if (isPublicPath(path)) {
            return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
        }

        String token = resolveToken(exchange.getRequest());
        if (!StringUtils.hasText(token)) {
            return unauthorized(exchange);
        }

        try {
            Claims claims = tokenProvider.parse(token);
            addInternalAuthHeaders(requestBuilder, claims);
            return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
        } catch (Exception ex) {
            return unauthorized(exchange);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(publicPath ->
                publicPath.endsWith("/") ? path.startsWith(publicPath) : path.equals(publicPath));
    }

    private void removeInternalAuthHeaders(ServerHttpRequest.Builder requestBuilder) {
        requestBuilder.headers(headers -> {
            headers.remove(AuthHeaders.SUBJECT_ID);
            headers.remove(AuthHeaders.SUBJECT_TYPE);
            headers.remove(AuthHeaders.USERNAME);
            headers.remove(AuthHeaders.AUTHORITIES);
            headers.remove(AuthHeaders.GATEWAY_SECRET);
        });
    }

    private void addInternalAuthHeaders(ServerHttpRequest.Builder requestBuilder, Claims claims) {
        requestBuilder.headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION));
        requestBuilder.header(AuthHeaders.SUBJECT_ID, claims.getSubject());
        addHeaderIfPresent(requestBuilder, AuthHeaders.SUBJECT_TYPE, claims.get("subjectType"));
        addHeaderIfPresent(requestBuilder, AuthHeaders.USERNAME, claims.get("username"));
        requestBuilder.header(AuthHeaders.AUTHORITIES, String.join(",", extractAuthorities(claims)));
        requestBuilder.header(AuthHeaders.GATEWAY_SECRET, gatewaySecret);
    }

    private void addHeaderIfPresent(ServerHttpRequest.Builder requestBuilder, String name, Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            requestBuilder.header(name, text);
        }
    }

    private List<String> extractAuthorities(Claims claims) {
        Object raw = claims.get("authorities");
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private String resolveToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        byte[] body = "{\"code\":401,\"message\":\"unauthorized\",\"data\":null}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory()
                .wrap(body)));
    }
}
