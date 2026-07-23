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
            "/api/admin/auth/password/change",
            "/api/admin/auth/logout");
    private static final Set<String> ANY_ADMIN_PATHS = Set.of(
            "/api/admin/auth/me",
            "/api/admin/auth/password/change",
            "/api/admin/auth/logout",
            "/api/admin/options/*/*");
    // 经典 RBAC 域级前缀兜底：GET 需 <域>_*_read，非 GET 需 <域>_*（非 _read 结尾，含 write/high）。
    // null 前缀 = 仅验认证。路径域→权限码前缀映射（treasury→finance / teams→network / market→finprod / support→service 为跨域）。
    private static final List<Rule> RULES = List.of(
            rule("/api/admin/platform/audit/**", "platform_"),
            rule("/api/admin/platform/events/**", "platform_a4_"),
            rule("/api/admin/platform/**", "platform_"),
            rule("/api/admin/ops-dashboard/**", "overview_"),
            rule("/api/admin/funnel", "overview_b3_"),
            rule("/api/admin/funnel/**", "overview_b3_"),
            rule("/api/admin/phase", "overview_b4_"),
            rule("/api/admin/phase/**", "overview_b4_"),
            rule("/api/admin/commands/**", "platform_"),
            rule("/api/admin/media/**", null),
            rule("/api/admin/users/**", "user_"),
            rule("/api/admin/bills", "finance_d4_"),
            rule("/api/admin/bills/**", "finance_d4_"),
            // Canonical D5 PUT authorization is field-aware (including H1 read-only fields),
            // so the controller's exact @PreAuthorize rule is the single write gate here.
            rule("/api/admin/withdraw/**", null),
            rule("/api/admin/finance/**", "finance_"),
            rule("/api/admin/treasury/b-domain", "overview_"),
            rule("/api/admin/treasury/b-domain/**", "overview_"),
            rule("/api/admin/treasury/**", "finance_"),
            rule("/api/admin/config/task-pricing", "device_"),
            rule("/api/admin/config/phone-tiers", "device_"),
            rule("/api/admin/devices/**", "device_"),
            rule("/api/admin/teams/**", "network_"),
            rule("/api/admin/market/**", "finprod_"),
            rule("/api/admin/repurchase/**", "finprod_"),
            rule("/api/admin/growth/**", "growth_"),
            rule("/api/admin/content/tickets", "service_"),
            rule("/api/admin/content/tickets/**", "service_"),
            rule("/api/admin/content/conversations", "service_"),
            rule("/api/admin/content/conversations/**", "service_"),
            rule("/api/admin/content/knowledge", "service_"),
            rule("/api/admin/content/knowledge/**", "service_"),
            rule("/api/admin/content/session-templates", "service_"),
            rule("/api/admin/content/session-templates/**", "service_"),
            rule("/api/admin/content/support-agents", "service_"),
            rule("/api/admin/content/support-agents/**", "service_"),
            rule("/api/admin/content/support-workbench", "service_"),
            rule("/api/admin/content/support-workbench/**", "service_"),
            rule("/api/admin/content/**", "content_"),
            rule("/api/admin/emergency-control/**", "emergency_"),
            rule("/api/admin/emergency/kill-switches/alerts", null),
            rule("/api/admin/emergency/**", "emergency_"),
            rule("/api/admin/janus/**", "risk_k6_"),
            rule("/api/admin/risk/radar", "overview_b5_"),
            rule("/api/admin/risk/radar/**", "overview_b5_"),
            rule("/api/admin/risk/bankrun-thresholds", "overview_b5_"),
            rule("/api/admin/risk/bankrun-thresholds/**", "overview_b5_"),
            rule("/api/admin/risk/alert-subscription", "overview_b5_"),
            rule("/api/admin/risk/**", "risk_"),
            rule("/api/admin/regulatory/**", "bi_"),
            rule("/api/admin/bi/**", "bi_"));

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final AuditLogService auditLogService;
    private final AdminAccountStateMapper accountStateMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(ADMIN_PREFIX)
                || "/api/admin/auth/login".equals(path)
                || "/api/admin/auth/mfa/verify".equals(path)) {
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
                .map(rule -> rule.domainPrefix() == null
                        ? RequiredAuthority.authenticated()
                        : (read ? RequiredAuthority.domainRead(rule.domainPrefix())
                                : RequiredAuthority.domainWrite(rule.domainPrefix())))
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

    private static Rule rule(String pattern, String domainPrefix) {
        return new Rule(pattern, domainPrefix);
    }

    private record Rule(String pattern, String domainPrefix) {
    }

    private record RequiredAuthority(String domainPrefix, boolean read, boolean authenticatedOnly) {
        static RequiredAuthority domainRead(String prefix) {
            return new RequiredAuthority(prefix, true, false);
        }

        static RequiredAuthority domainWrite(String prefix) {
            return new RequiredAuthority(prefix, false, false);
        }

        static RequiredAuthority authenticated() {
            return new RequiredAuthority(null, false, true);
        }

        String describe() {
            return authenticatedOnly ? "AUTHENTICATED" : domainPrefix + (read ? "*_read" : "*_(write/high)");
        }

        boolean matches(Collection<String> actualAuthorities) {
            if (authenticatedOnly || domainPrefix == null) {
                return true;
            }
            return actualAuthorities.stream().anyMatch(authority -> authority.startsWith(domainPrefix)
                    && (read ? authority.endsWith("_read") : !authority.endsWith("_read")));
        }
    }
}
