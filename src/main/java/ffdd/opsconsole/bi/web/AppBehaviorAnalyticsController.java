package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.bi.application.BehaviorAnalyticsService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/analytics")
@RequiredArgsConstructor
public class AppBehaviorAnalyticsController {
    private final BehaviorAnalyticsService service;

    @PostMapping("/events")
    public ApiResult<Map<String, Object>> ingest(Authentication authentication, @RequestBody BehaviorEventRequest request) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_AUTH_REQUIRED") : service.ingest(userId, request);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            return Long.valueOf(String.valueOf(authentication.getName()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
