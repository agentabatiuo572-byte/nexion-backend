package ffdd.opsconsole.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ImpersonationReadOnlyEnforcementFilter extends OncePerRequestFilter {
    private static final String VIEW_PREFIX = "/api/impersonation/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isImpersonation(authentication)) {
            filterChain.doFilter(request, response);
            return;
        }
        boolean safeMethod = HttpMethod.GET.matches(request.getMethod())
                || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod());
        if (!safeMethod) {
            reject(response, "IMPERSONATION_READ_ONLY");
            return;
        }
        if (!request.getRequestURI().startsWith(VIEW_PREFIX)) {
            reject(response, "IMPERSONATION_SCOPE_DENIED");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isImpersonation(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getDetails() instanceof Map<?, ?> details)) {
            return false;
        }
        return "IMPERSONATION".equals(String.valueOf(details.get("subjectType")));
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
    }
}
