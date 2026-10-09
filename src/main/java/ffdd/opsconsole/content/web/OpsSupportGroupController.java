package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.application.SupportGroupService;
import ffdd.opsconsole.content.dto.SupportGroupRequests.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX+"/content/support-agents/groups")
@RequiredArgsConstructor
public class OpsSupportGroupController {
    private final SupportGroupService groups;
    private final ProductionSupportPathGuard production;
    @GetMapping @PreAuthorize("hasAuthority('service_m1_read')")
    public Object groups(){return ffdd.opsconsole.shared.api.ApiResult.ok(groups.groups());}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('service_m1_read')")
    public Object group(@PathVariable Long id){return ffdd.opsconsole.shared.api.ApiResult.ok(groups.detail(id));}
    @GetMapping("/supervisors") @PreAuthorize("hasAuthority('platform_a1_read')")
    public Object supervisors(){return ffdd.opsconsole.shared.api.ApiResult.ok(groups.supervisors());}
    @GetMapping("/members/{adminId}") @PreAuthorize("hasAuthority('service_m1_read')")
    public Object member(@PathVariable Long adminId){return ffdd.opsconsole.shared.api.ApiResult.ok(groups.memberTarget(adminId));}
    @PostMapping @PreAuthorize("hasAuthority('service_m1_write')")
    public Object create(@RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String key,@RequestBody Create request){production.requireOpsWriteAllowed();return groups.create(key,request);}
    @PatchMapping("/{id}/name") @PreAuthorize("hasAuthority('service_m1_write')")
    public Object rename(@PathVariable Long id,@RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String key,@RequestBody Rename request){production.requireOpsWriteAllowed();return groups.rename(id,key,request);}
    @PatchMapping("/{id}/status") @PreAuthorize("hasAuthority('service_m1_write')")
    public Object status(@PathVariable Long id,@RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String key,@RequestBody Status request){production.requireOpsWriteAllowed();return groups.status(id,key,request);}
    @PatchMapping("/{id}/owner") @PreAuthorize("hasAuthority('service_m1_write')")
    public Object owner(@PathVariable Long id,@RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String key,@RequestBody Owner request){production.requireOpsWriteAllowed();return groups.owner(id,key,request);}
    @PatchMapping("/members/{adminId}") @PreAuthorize("hasAuthority('service_m1_write')")
    public Object member(@PathVariable Long adminId,@RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String key,@RequestBody Move request){production.requireOpsWriteAllowed();return groups.move(adminId,key,request);}
    @PatchMapping("/customer-routes/{customerId}") @PreAuthorize("hasAuthority('service_m1_write')")
    public Object route(@PathVariable Long customerId,@RequestHeader(OpsAdminApi.IDEMPOTENCY_KEY_HEADER) String key,
            @RequestBody ffdd.opsconsole.content.dto.SupportGroupRequests.Route request){production.requireOpsWriteAllowed();return groups.route(customerId,key,request);}
}
