package ffdd.opsconsole.shared.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokenProvider;
    private final JdbcTemplate jdbcTemplate;
    private final String gatewaySecret;

    public JwtAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            JdbcTemplate jdbcTemplate,
            @Value("${nexion.gateway.internal-secret:nexion-local-gateway-secret}") String gatewaySecret) {
        this.tokenProvider = tokenProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.gatewaySecret = gatewaySecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = tokenProvider.parse(token);
                if (isSessionActive(claims)) {
                    List<SimpleGrantedAuthority> authorities = extractAuthorities(claims);
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            claims.getSubject(), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    SecurityContextHolder.clearContext();
                }
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        if (token == null) {
            authenticateFromGatewayHeaders(request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateFromGatewayHeaders(HttpServletRequest request) {
        String requestSecret = request.getHeader(AuthHeaders.GATEWAY_SECRET);
        if (!StringUtils.hasText(requestSecret) || !requestSecret.equals(gatewaySecret)) {
            return;
        }
        String subjectId = request.getHeader(AuthHeaders.SUBJECT_ID);
        String authoritiesHeader = request.getHeader(AuthHeaders.AUTHORITIES);
        if (!StringUtils.hasText(subjectId)) {
            return;
        }
        List<SimpleGrantedAuthority> authorities = List.of();
        if (StringUtils.hasText(authoritiesHeader)) {
            authorities = Arrays.stream(authoritiesHeader.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(SimpleGrantedAuthority::new)
                    .toList();
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                subjectId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7);
    }

    private boolean isSessionActive(Claims claims) {
        Object subjectTypeClaim = claims.get("subjectType");
        String subjectType = subjectTypeClaim == null ? "USER" : String.valueOf(subjectTypeClaim);
        if (!"USER".equals(subjectType)) {
            return true;
        }
        String sessionId = claims.get("sessionId", String.class);
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        try {
            Integer activeCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(1)
                      FROM nx_user_session
                     WHERE refresh_token_id = ?
                       AND user_id = ?
                       AND revoked_at IS NULL
                       AND expires_at > NOW()
                       AND is_deleted = 0
                    """, Integer.class, sessionId, Long.valueOf(claims.getSubject()));
            return activeCount != null && activeCount > 0;
        } catch (DataAccessException | NumberFormatException ex) {
            return false;
        }
    }

    private List<SimpleGrantedAuthority> extractAuthorities(Claims claims) {
        Object raw = claims.get("authorities");
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
