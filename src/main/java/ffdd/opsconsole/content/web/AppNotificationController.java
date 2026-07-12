package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppNotificationService;
import ffdd.opsconsole.content.domain.AppNotificationPage;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class AppNotificationController {
    private final AppNotificationService service;

    @GetMapping
    public ApiResult<AppNotificationPage> page(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String priority,
            Authentication authentication) {
        return service.page(authenticatedUserId(authentication), cursor, priority, limit);
    }

    @PostMapping("/{notificationId}/read")
    public ApiResult<Void> markRead(@PathVariable Long notificationId, Authentication authentication) {
        return service.markRead(authenticatedUserId(authentication), notificationId);
    }

    @PostMapping("/read-all")
    public ApiResult<Integer> markAllRead(Authentication authentication) {
        return service.markAllRead(authenticatedUserId(authentication));
    }

    @DeleteMapping("/read")
    public ApiResult<Integer> clearRead(Authentication authentication) {
        return service.clearRead(authenticatedUserId(authentication));
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
