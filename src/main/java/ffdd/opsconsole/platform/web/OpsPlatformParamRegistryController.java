package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsPlatformParamRegistryService;
import ffdd.opsconsole.platform.dto.PlatformParamRegistryOverview;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/params-registry")
@RequiredArgsConstructor
public class OpsPlatformParamRegistryController {
    private final OpsPlatformParamRegistryService service;

    @GetMapping
    @PreAuthorize("hasAuthority('platform_a5_read')")
    public ApiResult<PlatformParamRegistryOverview> overview() {
        return service.overview();
    }
}
