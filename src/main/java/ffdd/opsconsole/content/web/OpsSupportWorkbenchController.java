package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support-workbench")
@RequiredArgsConstructor
public class OpsSupportWorkbenchController {
    private final OpsDeviceService deviceService;
    private final OpsUserService userService;

    // 设备 SKU 列表 — M1 客服总览 读
    @PreAuthorize("hasAuthority('service_m1_read')")
    @GetMapping("/skus")
    public ApiResult<PageResult<DeviceSkuView>> skus(DeviceSkuQueryRequest request) {
        return deviceService.skus(request);
    }

    // 用户账号列表 — M1 客服总览 读
    @PreAuthorize("hasAuthority('service_m1_read')")
    @GetMapping("/users")
    public ApiResult<PageResult<UserAccountView>> users(UserQueryRequest request) {
        return userService.profilePage(request);
    }
}
