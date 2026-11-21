package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.platform.application.AppUpdateService;
import ffdd.opsconsole.platform.application.AppUpdateService.Command;
import ffdd.opsconsole.platform.application.AppUpdateService.AdminView;
import ffdd.opsconsole.platform.application.AppUpdateService.PublicView;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AppUpdateController {
    private final AppUpdateService updates;

    @GetMapping("/api/app/updates/current")
    public ApiResult<PublicView> current(@RequestParam String platform, @RequestParam int build,
            @RequestParam(required = false) Integer resourceBuild, @RequestParam(required = false) String appId,
            @RequestParam(required = false) String hostPackage) {
        return ApiResult.ok(updates.current(platform, build, resourceBuild, appId, hostPackage));
    }

    @GetMapping("/api/admin/platform/app-updates")
    @PreAuthorize("hasAuthority('platform_a3_read')")
    public ApiResult<AdminView> overview(@RequestParam String platform, Authentication admin) {
        return ApiResult.ok(updates.overview(platform, admin));
    }

    @PostMapping("/api/admin/platform/app-updates/{platform}/{action}")
    @PreAuthorize("hasAuthority('platform_a3_read') and hasAnyAuthority('platform_a3_write','platform_a2_operation_approve')")
    public ApiResult<AdminView> change(@PathVariable String platform, @PathVariable String action,
            @RequestBody Command request, Authentication admin) {
        return ApiResult.ok(updates.change(platform, action, request, admin));
    }
}
