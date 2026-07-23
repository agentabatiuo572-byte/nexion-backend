package ffdd.opsconsole.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class UserBlocklistEnforcementFilter extends OncePerRequestFilter {
    private final UserAccountBlocklistVerifier blocklistVerifier;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getDetails() instanceof Map<?, ?> details
                && "USER".equalsIgnoreCase(String.valueOf(details.get("subjectType")))) {
            Long userId = positiveLong(authentication.getPrincipal());
            if (userId != null && blocklistVerifier.isBlocked(userId)) {
                SecurityContextHolder.clearContext();
                SecurityConfig.writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "ACCOUNT_BLOCKLISTED");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private Long positiveLong(Object value) {
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
