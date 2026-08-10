package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import ffdd.opsconsole.growth.application.GrowthPublicStatsService;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PlatformComputeConfigController {
    private final OpsDeviceService deviceService;
    private final GrowthPublicStatsService publicStatsService;

    @GetMapping("/api/config/platform")
    public ApiResult<PlatformComputeConfigView> platformConfig() {
        ApiResult<PlatformComputeConfigView> computeResult = deviceService.platformComputeConfig();
        if (computeResult.getCode() != 0 || computeResult.getData() == null) {
            return computeResult;
        }
        ApiResult<java.util.Map<String, Object>> publicStats = publicStatsService.publicProjection();
        if (publicStats.getCode() != 0) {
            return ApiResult.fail(publicStats.getCode(), publicStats.getMessage());
        }
        PlatformComputeConfigView compute = computeResult.getData();
        return ApiResult.ok(new PlatformComputeConfigView(
                compute.featureFlags(), publicStats.getData(), compute.onlineBonus(),
                compute.computerCompute(), compute.updatedAt()));
    }
}
