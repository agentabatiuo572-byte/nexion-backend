package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AdminPermissionCache permissionCache;
    private final ImpersonationSessionVerifier impersonationSessionVerifier;
    private final PlatformConfigFacade configFacade;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = tokenProvider.parse(token);
                if (isSessionActive(claims)) {
                    List<SimpleGrantedAuthority> authorities = resolveAuthorities(claims);
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            claims.getSubject(), null, authorities);
                    String username = claims.get("username", String.class);
                    String sessionId = claims.get("sessionId", String.class);
                    Map<String, String> details = new LinkedHashMap<>();
                    details.put("subjectType", String.valueOf(claims.getOrDefault("subjectType", "USER")));
                    if (StringUtils.hasText(username)) {
                        details.put("username", username.trim());
                    }
                    if (StringUtils.hasText(sessionId)) {
                        details.put("sessionId", sessionId.trim());
                    }
                    authentication.setDetails(Map.copyOf(details));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    SecurityContextHolder.clearContext();
                }
            } catch (SessionStoreUnavailableException ex) {
                SecurityContextHolder.clearContext();
                writeSessionStoreUnavailable(response);
                return;
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
        if (!gatewayProperties.isHeaderAuthenticationEnabled()
                || !gatewayProperties.isTrustedProxy(request.getRemoteAddr())) {
            return;
        }
        String requestSecret = request.getHeader(AuthHeaders.GATEWAY_SECRET);
        if (!StringUtils.hasText(requestSecret) || !requestSecret.equals(gatewayProperties.getInternalSecret())) {
            return;
        }
        String subjectId = request.getHeader(AuthHeaders.SUBJECT_ID);
        String subjectType = request.getHeader(AuthHeaders.SUBJECT_TYPE);
        String authoritiesHeader = request.getHeader(AuthHeaders.AUTHORITIES);
        if (!StringUtils.hasText(subjectId) || !StringUtils.hasText(subjectType)) {
            return;
        }
        String normalizedSubjectType = subjectType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("USER", "ADMIN").contains(normalizedSubjectType)) {
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
        String username = request.getHeader(AuthHeaders.USERNAME);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("subjectType", normalizedSubjectType);
        if (StringUtils.hasText(username)) {
            details.put("username", username.trim());
        }
        authentication.setDetails(Map.copyOf(details));
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
                throw new SessionStoreUnavailableException(ex);
            }
        }
        if (!"USER".equals(subjectType)) {
            if (!"IMPERSONATION".equals(subjectType)) {
                return false;
            }
            String sessionId = claims.get("sessionId", String.class);
            if (!StringUtils.hasText(sessionId)) {
                return false;
            }
            return impersonationSessionVerifier.isActive(Long.valueOf(claims.getSubject()), sessionId);
        }
        String sessionId = claims.get("sessionId", String.class);
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        try {
            int idleDays = configInt("auth.session.idle_ttl_days", 30, 7, 90);
            int activeCount = authSessionMapper.touchActiveUserSession(
                    sessionId, Long.valueOf(claims.getSubject()), idleDays);
            return activeCount > 0;
        } catch (RuntimeException ex) {
            throw new SessionStoreUnavailableException(ex);
        }
    }

    private int configInt(String key, int fallback, int min, int max) {
        try {
            int value = configFacade.activeValue(key)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .orElse(fallback);
            return value < min || value > max ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void writeSessionStoreUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(
                "{\"code\":503,\"message\":\"ADMIN_SESSION_STORE_UNAVAILABLE\",\"data\":null}");
    }

    private static final class SessionStoreUnavailableException extends RuntimeException {
        private SessionStoreUnavailableException(RuntimeException cause) {
            super(cause);
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

    /** ADMIN 权限从 Redis 缓存读（miss 回源 MySQL）；USER 仍从 JWT claim 读（本次只改管理端 RBAC）。 */
    private List<SimpleGrantedAuthority> resolveAuthorities(Claims claims) {
        Object subjectTypeClaim = claims.get("subjectType");
        String subjectType = subjectTypeClaim == null ? "USER" : String.valueOf(subjectTypeClaim);
        if ("ADMIN".equals(subjectType)) {
            try {
                Long adminId = Long.valueOf(claims.getSubject());
                return permissionCache.getPermissionCodes(adminId).stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
            } catch (RuntimeException ex) {
                return List.of();
            }
        }
        return extractAuthorities(claims);
    }
}
