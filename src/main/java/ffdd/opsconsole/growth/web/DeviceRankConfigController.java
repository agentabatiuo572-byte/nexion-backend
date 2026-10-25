package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.growth.application.DeviceRankConfigService;
import ffdd.opsconsole.growth.application.OpsGrowthCommandBoundary;
import ffdd.opsconsole.growth.dto.DeviceRankConfigRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/growth/device-rank-config")
@RequiredArgsConstructor
public class DeviceRankConfigController {
    private final DeviceRankConfigService service;
    private final OpsGrowthCommandBoundary commands;

    @GetMapping
    @PreAuthorize("hasAuthority('growth_h9_read')")
    public ApiResult<Map<String, Object>> overview() { return service.overview(); }

    @PatchMapping
    @PreAuthorize("hasAuthority('growth_h9_write')")
    public ApiResult<Map<String, Object>> update(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @RequestBody DeviceRankConfigRequest request) {
        return commands.execute("H9", "DEVICE_RANK_UPDATE", service.scope().key(), key, request, () -> service.update(request, key));
    }
}
