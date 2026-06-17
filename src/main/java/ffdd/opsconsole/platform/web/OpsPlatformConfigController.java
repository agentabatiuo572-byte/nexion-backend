package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsPlatformConfigService;
import ffdd.opsconsole.platform.dto.PlatformConfigOverview;
import ffdd.opsconsole.platform.dto.PlatformConfigResponse;
import ffdd.opsconsole.platform.dto.PlatformConfigUpdateRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/config")
public class OpsPlatformConfigController {
    private final OpsPlatformConfigService configService;

    public OpsPlatformConfigController(OpsPlatformConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_READ')")
    public ApiResult<PlatformConfigOverview> overview() {
        return configService.overview();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('PERM_SYSTEM_WRITE')")
    public ApiResult<PlatformConfigResponse> update(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) PlatformConfigUpdateRequest request) {
        return configService.update(idempotencyKey, request);
    }
}
