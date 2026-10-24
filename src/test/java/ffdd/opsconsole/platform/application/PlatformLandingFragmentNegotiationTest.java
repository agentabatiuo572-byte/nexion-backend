package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import ffdd.opsconsole.device.web.PlatformComputeConfigController;
import ffdd.opsconsole.growth.application.GrowthPublicStatsService;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformLandingFragmentNegotiationTest {
    @Test
    void defaultAndFalseUseLegacyProjectionWhileTrueOptsIn() throws Exception {
        var devices = mock(OpsDeviceService.class);
        var stats = mock(GrowthPublicStatsService.class);
        var growth = mock(OpsGrowthService.class);
        var experience = mock(PlatformExperienceConfigService.class);
        var config = mock(PlatformConfigFacade.class);
        when(devices.platformComputeConfig()).thenReturn(ApiResult.ok(new PlatformComputeConfigView(
                new PlatformComputeConfigView.FeatureFlags(false, false, false, false), Map.of(), null, null, null, null, "now")));
        when(stats.publicProjection()).thenReturn(ApiResult.ok(Map.of()));
        when(growth.platformHomeFeatureFlags()).thenReturn(ApiResult.ok(new OpsGrowthService.HomeFeatureFlags(true, false)));
        when(experience.publicConfig(anyBoolean())).thenAnswer(call -> ApiResult.ok(new PlatformComputeConfigView.ShareConfig("", List.of(),
                new PlatformComputeConfigView.AppDownload((boolean) call.getArgument(0) ? "https://uvel.ai/#download" : "https://uvel.ai/", "", "", "", "1.0.0", Map.of(), "official"))));
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformComputeConfigController(devices, stats, growth, experience, config)).build();
        mvc.perform(get("/api/config/platform")).andExpect(status().isOk()).andExpect(jsonPath("$.data.share.appDownload.officialUrl").value("https://uvel.ai/"));
        mvc.perform(get("/api/config/platform").param("officialLinkFragments", "false")).andExpect(status().isOk()).andExpect(jsonPath("$.data.share.appDownload.officialUrl").value("https://uvel.ai/"));
        mvc.perform(get("/api/config/platform").param("officialLinkFragments", "true")).andExpect(status().isOk()).andExpect(jsonPath("$.data.share.appDownload.officialUrl").value("https://uvel.ai/#download"));
        verify(experience, times(2)).publicConfig(false);
        verify(experience).publicConfig(true);
    }
}
