package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class PlatformGlobalRateLimitFilter extends OncePerRequestFilter {
    static final String CONFIG_KEY = "platform.global_rate_limit_per_minute";
    private final PlatformConfigFacade config;
    private final StringRedisTemplate redis;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !(uri.startsWith("/api/") || uri.startsWith("/auth/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            long limit = Long.parseLong(config.activeValue(CONFIG_KEY).orElseThrow().trim());
            if (limit < 100 || limit > 1_000_000) throw new IllegalArgumentException("range");
            RouteBudget budget = budget(request, limit);
            long minute = Instant.now().getEpochSecond() / 60;
            String prefix = "ops:global-rate:" + budget.name() + ":";
            if (exceeded(prefix + "total:" + minute, budget.totalLimit())
                    || exceeded(prefix + budget.identity() + ":" + budget.route() + ":" + minute,
                            budget.identityRouteLimit())) {
                write(response, 429, "GLOBAL_RATE_LIMIT_EXCEEDED");
                return;
            }
        } catch (RuntimeException ex) {
            write(response, 503, "GLOBAL_RATE_LIMIT_UNAVAILABLE");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean exceeded(String key, long limit) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) throw new IllegalStateException("redis increment missing");
        if (count == 1) redis.expire(key, Duration.ofSeconds(120));
        return count > limit;
    }

    private RouteBudget budget(HttpServletRequest request, long configuredLimit) {
        String uri = request.getRequestURI().toLowerCase(Locale.ROOT);
        String name;
        long totalLimit;
        if (uri.startsWith("/api/admin/auth/") || uri.startsWith("/auth/")) {
            name = "admin-auth";
            totalLimit = Math.max(100, configuredLimit / 10);
        } else if (uri.contains("/callback") || uri.contains("/callbacks/")) {
            name = "provider-callback";
            totalLimit = Math.max(500, configuredLimit / 2);
        } else if (uri.contains("/health") || uri.contains("/ready") || uri.contains("/live")) {
            name = "health";
            totalLimit = Math.max(120, configuredLimit / 20);
        } else {
            name = "general";
            totalLimit = configuredLimit;
        }
        // This servlet-order gate intentionally runs before Spring Security. Authorization is attacker-controlled
        // here, so this layer is always socket-IP based. The authenticated principal bucket lives after JWT.
        String identity = "ip-" + digest(request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr());
        return new RouteBudget(name, totalLimit, Math.max(20, totalLimit / 10), identity, routeFamily(uri));
    }

    private String routeFamily(String uri) {
        String[] segments = uri.split("/");
        StringBuilder family = new StringBuilder();
        for (int i = 1; i < Math.min(segments.length, 5); i++) {
            if (!segments[i].isBlank()) family.append('-').append(segments[i].replaceAll("[^a-z0-9_-]", "_"));
        }
        return digest(family.isEmpty() ? "root" : family.toString());
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record RouteBudget(
            String name, long totalLimit, long identityRouteLimit, String identity, String route) {}

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
