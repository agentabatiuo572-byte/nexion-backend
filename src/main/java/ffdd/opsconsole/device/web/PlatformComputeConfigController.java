package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import ffdd.opsconsole.growth.application.GrowthPublicStatsService;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import ffdd.opsconsole.platform.application.PlatformExperienceConfigService;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PlatformComputeConfigController {
    private final OpsDeviceService deviceService;
    private final GrowthPublicStatsService publicStatsService;
    private final OpsGrowthService growthService;
    private final PlatformExperienceConfigService experienceConfigService;

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
        ApiResult<OpsGrowthService.HomeFeatureFlags> homeFlags = growthService.platformHomeFeatureFlags();
        if (homeFlags.getCode() != 0 || homeFlags.getData() == null) {
            return ApiResult.fail(homeFlags.getCode(), homeFlags.getMessage());
        }
        ApiResult<PlatformComputeConfigView.ShareConfig> experience = experienceConfigService.publicConfig();
        if (experience.getCode() != 0 || experience.getData() == null) {
            return ApiResult.fail(experience.getCode(), experience.getMessage());
        }
        PlatformComputeConfigView compute = computeResult.getData();
        OpsGrowthService.HomeFeatureFlags flags = homeFlags.getData();
        PlatformComputeConfigView.FeatureFlags featureFlags = new PlatformComputeConfigView.FeatureFlags(
                compute.featureFlags().computeShareEnabled(), flags.homeNewcomerTasksEnabled(), flags.homeWeeklyPromoEnabled());
        return ApiResult.ok(new PlatformComputeConfigView(
                featureFlags, publicStats.getData(), compute.onlineBonus(),
                compute.computerCompute(), experience.getData(), compute.updatedAt()));
    }
}
