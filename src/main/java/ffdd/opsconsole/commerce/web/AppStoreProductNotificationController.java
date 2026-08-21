package ffdd.opsconsole.commerce.web;

import ffdd.opsconsole.commerce.application.AppStoreProductNotificationService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/store/notifications")
@RequiredArgsConstructor
public class AppStoreProductNotificationController {
    private final AppStoreProductNotificationService service;

    @GetMapping
    public ApiResult<AppStoreProductNotificationService.NotificationListView> list(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.list(userId);
    }

    @GetMapping("/{productNo}")
    public ApiResult<AppStoreProductNotificationService.NotificationView> status(
            @PathVariable String productNo, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.status(userId, productNo);
    }

    @PostMapping("/{productNo}")
    public ApiResult<AppStoreProductNotificationService.NotificationView> subscribe(
            @PathVariable String productNo, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.subscribe(userId, productNo);
    }

    @PostMapping
    public ApiResult<AppStoreProductNotificationService.NotificationView> subscribeBody(
            @RequestBody(required = false) ProductNotificationRequest request, Authentication authentication) {
        Long userId = userId(authentication);
        String productNo = request == null ? null : request.value();
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.subscribe(userId, productNo);
    }

    @DeleteMapping("/{productNo}")
    public ApiResult<AppStoreProductNotificationService.NotificationView> unsubscribe(
            @PathVariable String productNo, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.unsubscribe(userId, productNo);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) { return null; }
    }

    public record ProductNotificationRequest(String productNo, String sku) {
        String value() { return productNo == null || productNo.isBlank() ? sku : productNo; }
    }
}
