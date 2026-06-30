package ffdd.opsconsole.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class AdminRbacAuthorizationFilter extends OncePerRequestFilter {
    private static final String ADMIN_PREFIX = "/api/admin/";
    private static final Set<String> ANY_ADMIN_PATHS = Set.of(
            "/api/admin/auth/me",
            "/api/admin/options/*/*");
    private static final Set<String> ADMIN_WRITE_AUTHORITIES = Set.of(
            "PERM_SYSTEM_WRITE",
            "PERM_AUDIT_EXPORT",
            "PERM_TREASURY_WRITE",
            "PERM_USER_WRITE",
            "PERM_WITHDRAWAL_REVIEW",
            "PERM_DEVICE_WRITE",
            "PERM_DEVICE_RESTORE",
            "PERM_TEAM_WRITE",
            "PERM_MARKET_WRITE",
            "PERM_GROWTH_WRITE",
            "PERM_CONTENT_WRITE",
            "PERM_EMERGENCY_WRITE",
            "PERM_RISK_WRITE",
            "PERM_BI_EXPORT");
    private static final List<Rule> RULES = List.of(
            rule("/api/admin/platform/audit/exports/**", "PERM_AUDIT_EXPORT", "PERM_AUDIT_EXPORT"),
            rule("/api/admin/platform/audit/operations/**", "PERM_AUDIT_READ", "PERM_AUDIT_EXPORT"),
            rule("/api/admin/platform/audit/mechanism-params/**", "PERM_AUDIT_READ", "PERM_AUDIT_EXPORT"),
            rule("/api/admin/platform/audit/**", "PERM_AUDIT_READ", "PERM_AUDIT_EXPORT"),
            rule("/api/admin/platform/events/**", "PERM_AUDIT_READ", "PERM_SYSTEM_WRITE"),
            rule("/api/admin/platform/**", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE"),
            rule("/api/admin/ops-dashboard/**", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE"),
            rule("/api/admin/commands/**", "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE"),
            rule("/api/admin/media/**", null, "ANY_ADMIN_WRITE"),
            rule("/api/admin/users/**", "PERM_USER_READ", "PERM_USER_WRITE"),
            rule("/api/admin/finance/**", "PERM_WITHDRAWAL_READ", "PERM_WITHDRAWAL_REVIEW"),
            rule("/api/admin/treasury/**", "PERM_TREASURY_READ", "PERM_TREASURY_WRITE"),
            rule("/api/admin/devices/*/restore", "PERM_DEVICE_READ", "PERM_DEVICE_RESTORE"),
            rule("/api/admin/devices/**", "PERM_DEVICE_READ", "PERM_DEVICE_WRITE"),
            rule("/api/admin/teams/**", "PERM_TEAM_READ", "PERM_TEAM_WRITE"),
            rule("/api/admin/market/**", "PERM_MARKET_READ", "PERM_MARKET_WRITE"),
            rule("/api/admin/growth/**", "PERM_GROWTH_READ", "PERM_GROWTH_WRITE"),
            rule("/api/admin/content/**", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE"),
            rule("/api/admin/emergency-control/**", "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE"),
            rule("/api/admin/emergency/**", "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE"),
            rule("/api/admin/risk/**", "PERM_RISK_READ", "PERM_RISK_WRITE"),
            rule("/api/admin/bi/exports/**", "PERM_BI_EXPORT", "PERM_BI_EXPORT"),
            rule("/api/admin/bi/reports/*/*", "PERM_BI_READ", "PERM_BI_EXPORT"),
            rule("/api/admin/bi/**", "PERM_BI_READ", "PERM_BI_EXPORT"));

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(ADMIN_PREFIX) || "/api/admin/auth/login".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_AUTH_REQUIRED");
            return;
        }
        if (matchesAnyAdminPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        RequiredAuthority required = requiredAuthority(path, request.getMethod());
        if (required == null) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_RBAC_RULE_MISSING");
            return;
        }
        if (!required.matches(authorities)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_PERMISSION_DENIED");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matchesAnyAdminPath(String path) {
        return ANY_ADMIN_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private RequiredAuthority requiredAuthority(String path, String method) {
        boolean read = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
        return RULES.stream()
                .filter(rule -> pathMatcher.match(rule.pattern(), path))
                .findFirst()
                .map(rule -> {
                    String authority = read ? rule.readAuthority() : rule.writeAuthority();
                    return StringUtils.hasText(authority)
                            ? RequiredAuthority.single(authority)
                            : RequiredAuthority.authenticated();
                })
                .orElse(null);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    private static Rule rule(String pattern, String readAuthority, String writeAuthority) {
        return new Rule(pattern, readAuthority, writeAuthority);
    }

    private record Rule(String pattern, String readAuthority, String writeAuthority) {
    }

    private record RequiredAuthority(String authority, boolean authenticatedOnly) {
        static RequiredAuthority single(String authority) {
            return new RequiredAuthority(authority, false);
        }

        static RequiredAuthority authenticated() {
            return new RequiredAuthority(null, true);
        }

        boolean matches(Collection<String> actualAuthorities) {
            if (authenticatedOnly) {
                return true;
            }
            if ("ANY_ADMIN_WRITE".equals(authority)) {
                return actualAuthorities.stream().anyMatch(ADMIN_WRITE_AUTHORITIES::contains);
            }
            return StringUtils.hasText(authority) && actualAuthorities.contains(authority);
        }
    }
}
