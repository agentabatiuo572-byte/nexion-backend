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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Principal limiter registered explicitly after JwtAuthenticationFilter inside SecurityFilterChain. */
public class AuthenticatedPrincipalRateLimitFilter extends OncePerRequestFilter {
    private final PlatformConfigFacade config;
    private final StringRedisTemplate redis;
    private final boolean enabled;

    public AuthenticatedPrincipalRateLimitFilter(PlatformConfigFacade config, StringRedisTemplate redis) {
        this(config, redis, true);
    }

    private AuthenticatedPrincipalRateLimitFilter(
            PlatformConfigFacade config, StringRedisTemplate redis, boolean enabled) {
        this.config = config;
        this.redis = redis;
        this.enabled = enabled;
    }

    public static AuthenticatedPrincipalRateLimitFilter disabledForSlice() {
        return new AuthenticatedPrincipalRateLimitFilter(null, null, false);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            long configured = Long.parseLong(config.activeValue(PlatformGlobalRateLimitFilter.CONFIG_KEY)
                    .orElseThrow().trim());
            if (configured < 100 || configured > 1_000_000) throw new IllegalArgumentException("range");
            long limit = Math.max(20, configured / 10);
            long minute = Instant.now().getEpochSecond() / 60;
            String key = "ops:principal-rate:subject-" + digest(authentication.getName())
                    + ":" + routeFamily(request.getRequestURI().toLowerCase(Locale.ROOT)) + ":" + minute;
            Long count = redis.opsForValue().increment(key);
            if (count == null) throw new IllegalStateException("redis increment missing");
            if (count == 1) redis.expire(key, Duration.ofSeconds(120));
            if (count > limit) {
                write(response, 429, "PRINCIPAL_RATE_LIMIT_EXCEEDED");
                return;
            }
        } catch (RuntimeException ex) {
            write(response, 503, "PRINCIPAL_RATE_LIMIT_UNAVAILABLE");
            return;
        }
        chain.doFilter(request, response);
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

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
