package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppProductCatalogController {
    private final AppProductCatalogService service;

    /** Keeps the existing Java call shape for in-process callers and tests. */
    public ApiResult<Map<String, Object>> catalog(Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        return service.catalog(userId);
    }

    @GetMapping("/api/store/catalog")
    public ApiResult<Map<String, Object>> catalog(
            Authentication authentication,
            @RequestParam(value = "locale", required = false) String locale) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        return service.catalog(userId, locale);
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(authentication.getPrincipal()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
