package ffdd.opsconsole.platform.web;


import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsPlatformConfigService;
import ffdd.opsconsole.platform.application.PlatformExperienceConfigService;
import ffdd.opsconsole.platform.dto.PlatformConfigOverview;
import ffdd.opsconsole.platform.dto.PlatformConfigResponse;
import ffdd.opsconsole.platform.dto.PlatformConfigUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformExperienceConfigUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformExperienceConfigView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/config")
@RequiredArgsConstructor
public class OpsPlatformConfigController {
    private final OpsPlatformConfigService configService;
    private final PlatformExperienceConfigService experienceConfigService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('platform_a3_read')")
    public ApiResult<PlatformConfigOverview> overview() {
        return configService.overview();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('platform_a3_write')")
    public ApiResult<PlatformConfigResponse> update(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) PlatformConfigUpdateRequest request) {
        return configService.update(idempotencyKey, request);
    }

    @GetMapping("/experience")
    @PreAuthorize("hasAuthority('platform_a3_read')")
    public ApiResult<PlatformExperienceConfigView> experience() {
        return experienceConfigService.overview();
    }

    @PutMapping("/experience")
    @PreAuthorize("hasAuthority('platform_a3_write')")
    public ApiResult<PlatformExperienceConfigView> updateExperience(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) PlatformExperienceConfigUpdateRequest request) {
        return experienceConfigService.update(idempotencyKey, request);
    }
}
