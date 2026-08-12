package ffdd.opsconsole.commerce.web;

import ffdd.opsconsole.commerce.application.CommerceAcceptanceSandboxProfileCondition;
import ffdd.opsconsole.commerce.application.CommerceAcceptanceSandboxService;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.commerce.application.CommerceAcceptanceRun;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Does not register in production, even for an administrator. */
@RestController
@Conditional(CommerceAcceptanceSandboxProfileCondition.class)
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/commerce/acceptance/sandbox-orders")
@RequiredArgsConstructor
public class CommerceAcceptanceSandboxController {
    private final CommerceAcceptanceSandboxService service;
    private final CommerceAcceptanceSandboxMapper mapper;
    private final CommerceAcceptanceRun acceptanceRun;

    @GetMapping
    @PreAuthorize("hasAuthority('device_e4_write')")
    public ApiResult<java.util.Map<String, Object>> orders() {
        String runId = acceptanceRun.requireRunId();
        return ApiResult.ok(java.util.Map.of("source", "mock", "sourceEnvironment", "SANDBOX", "runId", runId,
                "orders", mapper.listAllSandboxOrders(runId, 200)));
    }

    @PostMapping("/{orderNo}/callbacks")
    @PreAuthorize("hasAuthority('device_e4_write')")
    public ApiResult<CommerceAcceptanceSandboxService.CallbackResult> callback(
            @PathVariable String orderNo, @RequestBody(required = false) CallbackRequest request) {
        if (request == null) return ApiResult.fail(422, "COMMERCE_SANDBOX_CALLBACK_BODY_REQUIRED");
        return ApiResult.ok(service.applyCallback(orderNo, request.eventId(), request.status(), request.expectedVersion(),
                request.reason(), AdminActorResolver.resolve(null)));
    }

    public record CallbackRequest(String eventId, String status, Long expectedVersion, String reason) { }
}
