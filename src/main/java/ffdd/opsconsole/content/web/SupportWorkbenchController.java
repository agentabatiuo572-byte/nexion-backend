package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.SupportMaintenanceService;
import ffdd.opsconsole.content.application.SupportWorkbenchService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.dto.SupportMaintenancePreferenceRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support-workbench")
@RequiredArgsConstructor
public class SupportWorkbenchController {
    private final SupportWorkbenchService workbench;
    private final SupportMaintenanceService maintenance;
    private final ProductionSupportPathGuard productionPathGuard;
    private final ffdd.opsconsole.content.application.SupportCustomerProfileService profiles;

    @GetMapping({"/overview","/customers"})
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> snapshot(@RequestParam(required=false) Long agentId,
            @RequestParam(defaultValue="ALL") String filter,@RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") long pageNum,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to,
            @RequestParam(required=false) ReadMode mode,@RequestParam(required=false) Long groupId) {
        productionPathGuard.requireOpsWriteAllowed();
        return ApiResult.ok(workbench.snapshot(mode,groupId,agentId,filter,keyword,pageNum,pageSize,from,to));
    }

    @GetMapping({"/customers/{customerId}","/customers/{customerId}/360"})
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> detail(@PathVariable Long customerId) {
        productionPathGuard.requireOpsWriteAllowed();
        return ApiResult.ok(workbench.detail(customerId));
    }
    @GetMapping("/customers/{customerId}/flows")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> flows(@PathVariable Long customerId,@RequestParam(defaultValue="1") long pageNum,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String currency,@RequestParam(required=false) String status,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        productionPathGuard.requireOpsWriteAllowed();return ApiResult.ok(profiles.flows(customerId,pageNum,pageSize,currency,status,from,to));
    }
    @GetMapping("/customers/{customerId}/avatar")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public org.springframework.http.ResponseEntity<byte[]> avatar(@PathVariable Long customerId) {
        productionPathGuard.requireOpsWriteAllowed();return OpsSupportAdminAvatarController.image(profiles.avatar(customerId));
    }
    @GetMapping("/customers/{customerId}/devices")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> devices(@PathVariable Long customerId,@RequestParam(defaultValue="1") long pageNum,
            @RequestParam(defaultValue="20") int pageSize) {
        productionPathGuard.requireOpsWriteAllowed();return ApiResult.ok(profiles.devices(customerId,pageNum,pageSize));
    }

    @GetMapping({"/customers/{customerId}/maintenance","/customers/{customerId}/maintenance/history"})
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> history(@PathVariable Long customerId,
            @RequestParam(defaultValue="1") long pageNum,@RequestParam(defaultValue="20") int pageSize) {
        productionPathGuard.requireOpsWriteAllowed();
        SupportWorkbenchService.requireSafeId(customerId); SupportWorkbenchService.validatePage(pageNum,pageSize);
        return ApiResult.ok(maintenance.history(customerId,pageNum,pageSize));
    }

    @PatchMapping("/customers/{customerId}/maintenance")
    @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<Map<String,Object>> change(@PathVariable Long customerId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody SupportMaintenancePreferenceRequest request) {
        productionPathGuard.requireOpsWriteAllowed();
        SupportWorkbenchService.requireSafeId(customerId);
        if(request==null || request.enabled()==null) throw new BizException(422,"SUPPORT_MAINTENANCE_REQUEST_REQUIRED");
        return maintenance.change(customerId,request.enabled(),request.reason(),request.expectedAssignmentId(),request.expectedVersion(),key);
    }
}
