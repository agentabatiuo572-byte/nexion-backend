package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.platform.application.OpsPlatformConfigService;
import ffdd.opsconsole.platform.dto.PlatformConfigOverview;
import ffdd.opsconsole.platform.dto.PlatformConfigResponse;
import ffdd.opsconsole.platform.dto.PlatformConfigUpdateRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsPlatformConfigControllerTest {
    private final OpsPlatformConfigService configService = mock(OpsPlatformConfigService.class);
    private final OpsPlatformConfigController controller = new OpsPlatformConfigController(configService);

    @Test
    void overviewDelegatesToPlatformApplicationService() {
        PlatformConfigOverview overview = new PlatformConfigOverview(
                List.of(),
                List.of(),
                List.of(),
                Map.of());
        when(configService.overview()).thenReturn(ApiResult.ok(overview));

        ApiResult<PlatformConfigOverview> result = controller.overview();

        assertThat(result.getData()).isSameAs(overview);
        verify(configService).overview();
    }

    @Test
    void updateDelegatesWithIdempotencyHeader() {
        PlatformConfigUpdateRequest request =
                new PlatformConfigUpdateRequest("flag", "core.sse_v2", null, "on", "rollout", "superadmin");
        PlatformConfigResponse response =
                new PlatformConfigResponse(1L, "feature.core.sse_v2", "on", "STRING", "admin_feature_flag", "ADMIN", "rollout", 1, null, null);
        when(configService.update("idem-1", request)).thenReturn(ApiResult.ok(response));

        ApiResult<PlatformConfigResponse> result = controller.update("idem-1", request);

        assertThat(result.getData()).isSameAs(response);
        verify(configService).update("idem-1", request);
    }
}
