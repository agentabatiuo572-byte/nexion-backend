package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsEventCenterService;
import ffdd.opsconsole.platform.dto.EventCenterMutationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import ffdd.opsconsole.platform.dto.EventDomainExtensionRequest;
import ffdd.opsconsole.platform.dto.EventSchemaRegistrationRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/events")
@PreAuthorize("hasAuthority('platform_a4_read')")
@RequiredArgsConstructor
public class OpsEventCenterController {
    private final OpsEventCenterService eventCenterService;

    @GetMapping("/overview")
    public ApiResult<EventCenterOverview> overview() {
        return eventCenterService.overview();
    }

    @PatchMapping("/params/{paramKey}")
    @PreAuthorize("hasAuthority('platform_a4_write')")
    public ApiResult<EventCenterOverview.EventDimensionParam> updateParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody(required = false) EventCenterMutationRequest request) {
        return eventCenterService.updateParam(idempotencyKey, paramKey, request);
    }

    @PostMapping("/schema-registrations")
    @PreAuthorize("hasAuthority('platform_a4_write')")
    public ApiResult<EventCenterOverview> registerSchema(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) EventSchemaRegistrationRequest request) {
        return eventCenterService.registerSchema(idempotencyKey, request);
    }

    @PostMapping("/domain-extension-batches")
    @PreAuthorize("hasAuthority('platform_a4_write')")
    public ApiResult<EventCenterOverview.EventDomainExtensionBatch> registerDomainExtension(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) EventDomainExtensionRequest request) {
        return eventCenterService.registerDomainExtension(idempotencyKey, request);
    }
}
