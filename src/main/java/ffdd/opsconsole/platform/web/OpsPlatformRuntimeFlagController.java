package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsPlatformConfigService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/flags")
@RequiredArgsConstructor
public class OpsPlatformRuntimeFlagController {
    private final OpsPlatformConfigService configService;

    @GetMapping("/runtime")
    @PreAuthorize("isAuthenticated()")
    public ApiResult<Map<String, Object>> runtimeFlags() {
        return configService.runtimeFlags();
    }
}
