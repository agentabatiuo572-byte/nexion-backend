package ffdd.opsconsole.commerce.web;

import ffdd.opsconsole.commerce.application.AppStorefrontActivityService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storefront")
@RequiredArgsConstructor
public class AppStorefrontActivityController {
    private final AppStorefrontActivityService service;

    @GetMapping("/activity")
    public ApiResult<Map<String, Object>> activity(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.activity(userId, cursor, limit);
    }

    @GetMapping("/products/{productNo}/social-proof")
    public ApiResult<Map<String, Object>> socialProof(
            @PathVariable String productNo,
            @RequestParam(required = false) Integer windowDays,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.socialProof(userId, productNo, windowDays);
    }

    private Long userId(Authentication authentication) {
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

    private ApiResult<Map<String, Object>> forbidden() {
        return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
    }
}
