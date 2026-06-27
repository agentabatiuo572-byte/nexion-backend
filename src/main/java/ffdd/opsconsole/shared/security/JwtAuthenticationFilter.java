package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokenProvider;
    private final AuthSessionMapper authSessionMapper;
    private final GatewaySecurityProperties gatewayProperties;
    private final AdminSessionRegistry adminSessionRegistry;

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
        if (!StringUtils.hasText(requestSecret) || !requestSecret.equals(gatewayProperties.getInternalSecret())) {
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
        if ("ADMIN".equals(subjectType)) {
            String sessionId = claims.get("sessionId", String.class);
            if (!StringUtils.hasText(sessionId)) {
                return false;
            }
            try {
                return adminSessionRegistry.isSessionActive(Long.valueOf(claims.getSubject()), sessionId);
            } catch (RuntimeException ex) {
                return false;
            }
        }
        if (!"USER".equals(subjectType)) {
            return false;
        }
        String sessionId = claims.get("sessionId", String.class);
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        try {
            int activeCount = authSessionMapper.countActiveUserSession(sessionId, Long.valueOf(claims.getSubject()));
            return activeCount > 0;
        } catch (RuntimeException ex) {
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
