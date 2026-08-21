package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.NotificationPreferenceService;
import ffdd.opsconsole.content.domain.NotificationPreferenceView;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/me/notification-preferences", "/api/notifications/preferences"})
@RequiredArgsConstructor
public class AppNotificationPreferenceController {
    private static final Set<String> CATEGORIES = Set.of(
            "commission", "team", "staking", "market", "genesis", "system");
    private final NotificationPreferenceService service;

    @GetMapping
    public ApiResult<NotificationPreferenceView> get(Authentication authentication) {
        return service.get(authenticatedUserId(authentication));
    }

    @PatchMapping
    public ApiResult<NotificationPreferenceView> patch(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return service.patch(null, null);
        if (request == null || request.isEmpty()) return service.patch(userId, null);
        for (Map.Entry<String, Object> entry : request.entrySet()) {
            if (!CATEGORIES.contains(entry.getKey())) {
                return ApiResult.fail(422, "NOTIFICATION_PREFERENCES_CATEGORY_INVALID");
            }
            if (!(entry.getValue() instanceof Boolean)) {
                return ApiResult.fail(422, "NOTIFICATION_PREFERENCES_VALUE_INVALID");
            }
        }
        return service.patch(userId, new NotificationPreferenceService.PatchRequest(
                booleanValue(request, "commission"),
                booleanValue(request, "team"),
                booleanValue(request, "staking"),
                booleanValue(request, "market"),
                booleanValue(request, "genesis"),
                booleanValue(request, "system")));
    }

    private Boolean booleanValue(Map<String, Object> request, String key) {
        return request.containsKey(key) ? (Boolean) request.get(key) : null;
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null) {
            return null;
        }
        if (!(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return null;
        }
        try {
            long userId = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
