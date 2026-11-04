package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.application.SupportUnboundPolicyService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support-agents/unbound-policy")
public class SupportUnboundPolicyController {
    private final SupportUnboundPolicyService service;
    private final ProductionSupportPathGuard production;

    @GetMapping @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String, Object>> read() { return ApiResult.ok(service.read()); }

    @PutMapping @PreAuthorize("hasAuthority('service_m1_write')")
    public ApiResult<Map<String, Object>> update(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @RequestBody SupportUnboundPolicyService.UpdateRequest request) {
        production.requireOpsWriteAllowed();
        return service.update(key == null ? null : key.trim(), request);
    }
}
