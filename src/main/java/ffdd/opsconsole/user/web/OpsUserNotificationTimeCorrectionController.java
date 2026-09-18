package ffdd.opsconsole.user.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.application.NotificationTimeCorrectionService;
import ffdd.opsconsole.user.application.NotificationTimeCorrectionService.CorrectionRequest;
import ffdd.opsconsole.user.application.NotificationTimeCorrectionService.CorrectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/users/profiles/{userKey}/notifications")
@RequiredArgsConstructor
public class OpsUserNotificationTimeCorrectionController {
    private final NotificationTimeCorrectionService service;

    @PostMapping("/{notificationId}/time-correction")
    @PreAuthorize("hasAuthority('user_c1hub_read') && hasAuthority('platform_a4_read') && hasAuthority('user_c1hub_write') && hasAuthority('platform_a4_write')")
    public ApiResult<CorrectionView> correct(@PathVariable String userKey, @PathVariable Long notificationId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CorrectionRequest request) {
        return ApiResult.ok(service.correct(userKey, notificationId, key, request));
    }
}
