package ffdd.opsconsole.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
    private static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";
    private static final Set<String> PASSWORD_CHANGE_REQUIRED_STATUSES = Set.of(
            PASSWORD_CHANGE_REQUIRED,
            "MAIL_DISPATCHED",
            "HANDOFF_PENDING");
    private static final Set<String> PASSWORD_CHANGE_ALLOWED_PATHS = Set.of(
            "/api/admin/auth/me",
            "/api/admin/auth/password/change");
    private static final Set<String> ANY_ADMIN_PATHS = Set.of(
            "/api/admin/auth/me",
            "/api/admin/auth/password/change",
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
            "PERM_SUPPORT_WRITE",
            "PERM_EMERGENCY_WRITE",
            "PERM_RISK_WRITE",
            "PERM_BI_EXPORT");
    private static final List<String> SUPPORT_OR_CONTENT_READ = List.of("PERM_SUPPORT_READ", "PERM_CONTENT_READ");
    private static final List<String> SUPPORT_OR_CONTENT_WRITE = List.of("PERM_SUPPORT_WRITE", "PERM_CONTENT_WRITE");
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
            ruleAny("/api/admin/content/tickets", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/tickets/**", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/conversations", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/conversations/**", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/knowledge", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/knowledge/**", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/session-templates", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/session-templates/**", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/support-agents", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/support-agents/**", SUPPORT_OR_CONTENT_READ, SUPPORT_OR_CONTENT_WRITE),
            ruleAny("/api/admin/content/support-workbench", SUPPORT_OR_CONTENT_READ, List.of("PERM_CONTENT_WRITE")),
            ruleAny("/api/admin/content/support-workbench/**", SUPPORT_OR_CONTENT_READ, List.of("PERM_CONTENT_WRITE")),
            rule("/api/admin/content/**", "PERM_CONTENT_READ", "PERM_CONTENT_WRITE"),
            rule("/api/admin/emergency-control/**", "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE"),
            rule("/api/admin/emergency/**", "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE"),
            rule("/api/admin/risk/**", "PERM_RISK_READ", "PERM_RISK_WRITE"),
            rule("/api/admin/bi/exports/**", "PERM_BI_EXPORT", "PERM_BI_EXPORT"),
            rule("/api/admin/bi/reports/*/*", "PERM_BI_READ", "PERM_BI_EXPORT"),
            rule("/api/admin/bi/**", "PERM_BI_READ", "PERM_BI_EXPORT"));

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final AuditLogService auditLogService;
    private final AdminAccountStateMapper accountStateMapper;

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
            auditDenial(request, null, "ADMIN_AUTH_REQUIRED", null);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_AUTH_REQUIRED");
            return;
        }
        if (!matchesPasswordChangeAllowedPath(path) && passwordChangeRequired(authentication)) {
            auditDenial(request, authentication, "ADMIN_PASSWORD_CHANGE_REQUIRED", null);
            reject(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_PASSWORD_CHANGE_REQUIRED");
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
            auditDenial(request, authentication, "ADMIN_RBAC_RULE_MISSING", null);
            reject(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_RBAC_RULE_MISSING");
            return;
        }
        if (!required.matches(authorities)) {
            auditDenial(request, authentication, "ADMIN_PERMISSION_DENIED", required.describe());
            reject(response, HttpServletResponse.SC_FORBIDDEN, "ADMIN_PERMISSION_DENIED");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matchesAnyAdminPath(String path) {
        return ANY_ADMIN_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private boolean matchesPasswordChangeAllowedPath(String path) {
        return PASSWORD_CHANGE_ALLOWED_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private boolean passwordChangeRequired(Authentication authentication) {
        Long adminId = parseAdminId(authentication.getPrincipal());
        if (adminId == null) {
            return false;
        }
        AdminAccountStateEntity state = accountStateMapper.selectActiveByAdminId(adminId);
        return state != null && PASSWORD_CHANGE_REQUIRED_STATUSES.contains(state.getCredentialDeliveryStatus());
    }

    private Long parseAdminId(Object principal) {
        if (principal == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(principal));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private RequiredAuthority requiredAuthority(String path, String method) {
        boolean read = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
        return RULES.stream()
                .filter(rule -> pathMatcher.match(rule.pattern(), path))
                .findFirst()
                .map(rule -> {
                    List<String> authorities = read ? rule.readAuthorities() : rule.writeAuthorities();
                    return !authorities.isEmpty()
                            ? RequiredAuthority.anyOf(authorities)
                            : RequiredAuthority.authenticated();
                })
                .orElse(null);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    private void auditDenial(
            HttpServletRequest request, Authentication authentication, String reason, String requiredAuthority) {
        if (auditLogService == null) {
            return;
        }
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("A1_RBAC_ACCESS_DENIED")
                .resourceType("ADMIN_RBAC")
                .resourceId(request.getRequestURI())
                .actorType("ADMIN")
                .actorUsername(authentication == null ? null : authentication.getName())
                .method(request.getMethod())
                .path(request.getRequestURI())
                .result("DENIED")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "reason", reason,
                        "requiredAuthority", requiredAuthority == null ? "" : requiredAuthority,
                        "method", request.getMethod(),
                        "path", request.getRequestURI()))
                .build());
    }

    private static Rule rule(String pattern, String readAuthority, String writeAuthority) {
        return ruleAny(
                pattern,
                StringUtils.hasText(readAuthority) ? List.of(readAuthority) : List.of(),
                StringUtils.hasText(writeAuthority) ? List.of(writeAuthority) : List.of());
    }

    private static Rule ruleAny(String pattern, List<String> readAuthorities, List<String> writeAuthorities) {
        return new Rule(pattern, List.copyOf(readAuthorities), List.copyOf(writeAuthorities));
    }

    private record Rule(String pattern, List<String> readAuthorities, List<String> writeAuthorities) {
    }

    private record RequiredAuthority(List<String> authorities, boolean authenticatedOnly) {
        static RequiredAuthority anyOf(List<String> authorities) {
            return new RequiredAuthority(List.copyOf(authorities), false);
        }

        static RequiredAuthority authenticated() {
            return new RequiredAuthority(List.of(), true);
        }

        String describe() {
            return String.join("|", authorities);
        }

        boolean matches(Collection<String> actualAuthorities) {
            if (authenticatedOnly) {
                return true;
            }
            return authorities.stream().anyMatch(authority -> {
                if ("ANY_ADMIN_WRITE".equals(authority)) {
                    return actualAuthorities.stream().anyMatch(ADMIN_WRITE_AUTHORITIES::contains);
                }
                return StringUtils.hasText(authority) && actualAuthorities.contains(authority);
            });
        }
    }
}
