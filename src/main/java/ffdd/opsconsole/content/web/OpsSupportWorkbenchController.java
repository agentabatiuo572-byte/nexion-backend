package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserProfileListView;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import java.util.List;
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
    public ApiResult<PageResult<UserProfileListView>> users(UserQueryRequest request) {
        ApiResult<PageResult<UserAccountView>> result = userService.profilePage(request);
        if (result.getCode() != 0 || result.getData() == null) {
            return ApiResult.fail(result.getCode(), result.getMessage());
        }
        PageResult<UserAccountView> page = result.getData();
        List<UserProfileListView> records = page.getRecords() == null
                ? List.of()
                : page.getRecords().stream()
                        .map(record -> UserProfileListView.from(record, "SUPPORT"))
                        .toList();
        return ApiResult.ok(new PageResult<>(page.getTotal(), page.getPageNum(), page.getPageSize(), records));
    }
}
