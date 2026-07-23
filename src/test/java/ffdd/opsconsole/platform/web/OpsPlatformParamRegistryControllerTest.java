package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.OpsPlatformParamRegistryService;
import ffdd.opsconsole.platform.dto.PlatformParamRegistryOverview;
import ffdd.opsconsole.platform.dto.PlatformParamRegistryStats;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsPlatformParamRegistryControllerTest {
    private final OpsPlatformParamRegistryService service = mock(OpsPlatformParamRegistryService.class);
    private final OpsPlatformParamRegistryController controller = new OpsPlatformParamRegistryController(service);

    @Test
    void overviewDelegatesAndUsesA5ReadPermission() throws Exception {
        PlatformParamRegistryOverview overview = new PlatformParamRegistryOverview(
                List.of(), new PlatformParamRegistryStats(0, 0, 0, 2), List.of(), "2026-07-18T10:00:00");
        when(service.overview()).thenReturn(ApiResult.ok(overview));

        assertThat(controller.overview().getData()).isSameAs(overview);
        verify(service).overview();
        PreAuthorize guard = OpsPlatformParamRegistryController.class.getMethod("overview")
                .getAnnotation(PreAuthorize.class);
        assertThat(guard.value()).isEqualTo("hasAuthority('platform_a5_read')");
    }
}
