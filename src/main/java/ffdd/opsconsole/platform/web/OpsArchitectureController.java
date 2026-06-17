package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsArchitectureService;
import ffdd.opsconsole.platform.dto.OpsArchitectureOverview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/architecture")
public class OpsArchitectureController {
    private final OpsArchitectureService architectureService;

    public OpsArchitectureController(OpsArchitectureService architectureService) {
        this.architectureService = architectureService;
    }

    @GetMapping
    public ApiResult<OpsArchitectureOverview> overview() {
        return ApiResult.ok(architectureService.overview());
    }
}
