package ffdd.opsconsole.user.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.application.NotificationTimeEvidenceService;
import ffdd.opsconsole.user.application.NotificationTimeEvidenceService.EvidenceView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/users/profiles/{userKey}/notifications")
@RequiredArgsConstructor
public class OpsUserNotificationEvidenceController {
    private final NotificationTimeEvidenceService service;

    @GetMapping("/{notificationId}/time-evidence")
    @PreAuthorize("hasAuthority('user_c1hub_read') && hasAuthority('platform_a4_read')")
    public ApiResult<EvidenceView> preview(@PathVariable String userKey, @PathVariable Long notificationId) {
        return service.preview(userKey, notificationId);
    }
}
