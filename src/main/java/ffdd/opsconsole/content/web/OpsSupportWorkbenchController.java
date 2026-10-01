package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.domain.SupportAdvisorUserOption;
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
    private final OpsSupportAgentService supportAgentService;
    private final ffdd.opsconsole.content.application.SupportOwnershipService ownership;
    private final ffdd.opsconsole.content.mapper.SupportBindingMapper bindings;

    // 设备 SKU 列表 — M1 客服总览 读
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    @GetMapping("/skus")
    public ApiResult<PageResult<DeviceSkuView>> skus(DeviceSkuQueryRequest request) {
        return deviceService.skus(new DeviceSkuQueryRequest("on",request==null?null:request.keyword(),request==null?null:request.pageNum(),request==null?null:request.pageSize()));
    }

    // 用户账号列表 — M1 客服总览 读
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    @GetMapping("/users")
    public ApiResult<PageResult<UserProfileListView>> users(UserQueryRequest request) {
        Long actor=ownership.actorId();
        if(!ownership.supervisor(actor)) {
            if(bindings.eligibleAgent(actor)!=1) return ApiResult.fail(403,"SUPPORT_AGENT_UNAVAILABLE");
            int page=request==null || request.pageNum()==null?1:Math.max(1,request.pageNum());
            int size=request==null || request.pageSize()==null?20:Math.max(1,Math.min(100,request.pageSize()));
            String keyword=request==null?null:request.keyword();
            String status=request==null?null:request.status();
            Long id=request==null?null:request.userId();
            long count=bindings.ownedCustomerCount(actor,keyword,status,id);
            var records=bindings.ownedCustomers(actor,keyword,status,id,size,(long)(page-1)*size).stream()
                    .map(customer->userService.profile(customer).getData()).filter(java.util.Objects::nonNull)
                    .map(record->UserProfileListView.from(record,"SUPPORT")).toList();
            return ApiResult.ok(new PageResult<>(count,page,size,records));
        }
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

    @PreAuthorize("hasAuthority('service_m1_write')")
    @GetMapping("/advisor-users")
    public ApiResult<PageResult<SupportAdvisorUserOption>> advisorUsers(UserQueryRequest request) {
        if (!supportAgentService.canManageSupportSeats()) {
            return ApiResult.fail(403, "SUPPORT_SEAT_MANAGEMENT_FORBIDDEN");
        }
        ApiResult<PageResult<UserAccountView>> result = userService.supportProfilePage(request);
        if (result.getCode() != 0 || result.getData() == null) {
            return ApiResult.fail(result.getCode(), result.getMessage());
        }
        PageResult<UserAccountView> page = result.getData();
        List<SupportAdvisorUserOption> records = page.getRecords() == null ? List.of()
                : page.getRecords().stream()
                        .map(user -> new SupportAdvisorUserOption(user.userId(), user.userNo(), user.nickname(), user.phoneMasked()))
                        .toList();
        return ApiResult.ok(new PageResult<>(page.getTotal(), page.getPageNum(), page.getPageSize(), records));
    }
}
