package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.content.terms.LegalTermsService;
import ffdd.opsconsole.content.terms.domain.LegalTermsCurrentView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Server-side gate for authenticated user business writes.  The App route
 * guard is presentation-only; this filter makes onboarding and the currently
 * published Terms authoritative for direct HTTP callers as well.
 */
@Component
@RequiredArgsConstructor
public class UserBusinessWriteGateFilter extends OncePerRequestFilter {
    private final UserOpsMapper users;
    private final LegalTermsService legalTerms;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!isUnsafeMethod(request.getMethod())) return true;
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.startsWith("/api/admin/")
                || path.startsWith("/api/onboarding/")
                || path.startsWith("/api/legal/")
                || path.startsWith("/api/app/profile/")
                || path.startsWith("/api/app/security/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Long userId = authenticatedUserId();
        if (userId == null) {
            filterChain.doFilter(request, response);
            return;
        }
        final boolean onboardingComplete;
        try {
            onboardingComplete = users.isOnboardingComplete(userId);
        } catch (RuntimeException ex) {
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "USER_ONBOARDING_STATE_UNAVAILABLE");
            return;
        }
        if (!onboardingComplete) {
            write(response, 428, "USER_ONBOARDING_REQUIRED");
            return;
        }
        final ApiResult<LegalTermsCurrentView> current;
        try {
            current = legalTerms.current(language(userId), "GLOBAL", userId);
        } catch (RuntimeException ex) {
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "LEGAL_TERMS_UNAVAILABLE");
            return;
        }
        if (current == null || current.getCode() != 0 || current.getData() == null) {
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "LEGAL_TERMS_UNAVAILABLE");
            return;
        }
        if (!current.getData().acknowledged()) {
            write(response, 428, "LEGAL_TERMS_ACK_REQUIRED");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String language(Long userId) {
        try {
            String value = users.activeUserLanguage(userId);
            return StringUtils.hasText(value) ? value.trim() : "en";
        } catch (RuntimeException ex) {
            return "en";
        }
    }

    private Long authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean isUnsafeMethod(String method) {
        return HttpMethod.POST.matches(method) || HttpMethod.PUT.matches(method)
                || HttpMethod.PATCH.matches(method) || HttpMethod.DELETE.matches(method);
    }

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
