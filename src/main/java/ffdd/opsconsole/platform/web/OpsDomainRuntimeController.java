package ffdd.opsconsole.platform.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsDomainRuntimeService;
import ffdd.opsconsole.platform.dto.OpsDomainCommandValidationRequest;
import ffdd.opsconsole.platform.dto.OpsDomainCommandValidationResponse;
import ffdd.opsconsole.platform.dto.OpsDomainRuntimeContract;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/runtime")
@PreAuthorize("hasAuthority('platform_a1_read')")
@RequiredArgsConstructor
public class OpsDomainRuntimeController {
    private final OpsDomainRuntimeService runtimeService;

    @GetMapping("/contracts")
    public ApiResult<List<OpsDomainRuntimeContract>> contracts() {
        return runtimeService.contracts();
    }

    @GetMapping("/contracts/{adminResource}")
    public ApiResult<OpsDomainRuntimeContract> contract(@PathVariable String adminResource) {
        return runtimeService.contract(adminResource);
    }

    @PostMapping("/contracts/{adminResource}/commands/validate")
    @PreAuthorize("hasAuthority('platform_a1_write')")
    public ApiResult<OpsDomainCommandValidationResponse> validateCommand(
            @PathVariable String adminResource,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) OpsDomainCommandValidationRequest request) {
        return runtimeService.validateCommand(adminResource, idempotencyKey, request);
    }
}
