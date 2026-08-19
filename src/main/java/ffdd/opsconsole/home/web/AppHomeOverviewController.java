package ffdd.opsconsole.home.web;

import ffdd.opsconsole.home.application.AppHomeOverviewService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/home")
@RequiredArgsConstructor
public class AppHomeOverviewController {
    private final AppHomeOverviewService service;

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.overview(userId);
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
}
