package ffdd.opsconsole.shared.security;

import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/** Resolves an auditable administrator identity from the authenticated security context. */
public final class AdminActorResolver {
    private AdminActorResolver() {
    }

    public static String resolve(String trustedInternalFallback) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return trim(trustedInternalFallback);
        }
        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> values) {
            Object username = values.get("username");
            if (username != null && StringUtils.hasText(String.valueOf(username))) {
                return String.valueOf(username).trim();
            }
        }
        Object principal = authentication.getPrincipal();
        String stableId = principal == null ? authentication.getName() : String.valueOf(principal);
        return StringUtils.hasText(stableId) ? "admin:" + stableId.trim() : trim(trustedInternalFallback);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
