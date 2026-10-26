package ffdd.opsconsole.shared.security;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Server-side gate for authenticated user business writes.  The App route
 * guard is presentation-only; onboarding remains authoritative for direct
 * HTTP callers. Terms consent belongs to registration, not later operations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserBusinessWriteGateFilter extends OncePerRequestFilter implements ffdd.opsconsole.common.boundary.UserTermsAcceptanceContract {
    private final UserOpsMapper users;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!isUnsafeMethod(request.getMethod())) return true;
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.startsWith("/api/admin/")
                || path.startsWith("/api/onboarding/")
                || path.startsWith("/api/legal/")
                || path.startsWith("/api/app/profile/")
                || path.startsWith("/api/app/security/")
                // Customers need support while completing initial setup.
                || isSupportCommunication(request)
                // Reading and opening a welcome notification is not a business command.
                || path.matches("/api/notifications/(?:[0-9]+/(?:actions|read)|read-all|read)");
    }

    private boolean isSupportCommunication(HttpServletRequest request) {
        String path = request.getRequestURI();
        return switch (request.getMethod()) {
            case "POST" -> path.equals("/api/app/support/realtime-ticket")
                    || path.equals("/api/app/support/conversations")
                    || path.matches("/api/app/support/conversations/[A-Za-z0-9_-]{1,100}/(?:replies|read|dismiss)")
                    || path.equals("/api/app/support/attachments");
            case "DELETE" -> path.matches("/api/app/support/attachments/[A-Za-z0-9_-]{1,100}");
            default -> false;
        };
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
        ApiResult<Void> blocked = businessWriteBlock(userId);
        if (blocked != null) {
            if (blocked.getCode() == 503 && "POST".equals(request.getMethod())
                    && "/api/app/deposits/vietqr/intents".equals(request.getRequestURI())) {
                String reason = switch (blocked.getMessage() == null ? "" : blocked.getMessage()) {
                    case "USER_ONBOARDING_STATE_UNAVAILABLE" -> blocked.getMessage();
                    default -> "UNCLASSIFIED_503";
                };
                log.warn("event=VIETQR_INTENT_CREATE_UNAVAILABLE phase=USER_WRITE_GATE code={}", reason);
            }
            write(response, blocked.getCode(), blocked.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Initial setup remains mandatory for business writes outside support communication. */
    public ApiResult<Void> businessWriteBlock(Long userId) {
        final boolean onboardingComplete;
        try {
            onboardingComplete = users.isOnboardingComplete(userId);
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "USER_ONBOARDING_STATE_UNAVAILABLE");
        }
        if (!onboardingComplete) {
            return ApiResult.fail(428, "USER_ONBOARDING_REQUIRED");
        }
        return null;
    }

    /** Compatibility for onboarding callers: no post-registration Terms check. */
    @Override
    public ApiResult<Void> checkTerms(Long userId) {
        return null;
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
