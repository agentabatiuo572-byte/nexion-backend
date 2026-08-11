package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.platform.application.OpsA1PermissionRegistrationService;
import ffdd.opsconsole.platform.dto.A1PermissionRegistrationRequest;
import ffdd.opsconsole.platform.dto.PermissionDictionaryView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/platform/accounts/permissions")
@RequiredArgsConstructor
public class OpsA1PermissionRegistrationController {
    private final OpsA1PermissionRegistrationService service;

    @PostMapping
    @PreAuthorize("hasAuthority('platform_a1_rbac_grants_update')")
    public ApiResult<PermissionDictionaryView> register(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) A1PermissionRegistrationRequest request) {
        A1PermissionRegistrationRequest authenticated = request == null ? null : new A1PermissionRegistrationRequest(
                request.permissionCode(), request.permissionName(), request.resourcePath(), request.permType(),
                request.amplifies(), request.expectedAbsent(), request.reason(), AdminActorResolver.resolve(null));
        return service.register(idempotencyKey, authenticated);
    }
}
