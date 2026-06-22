package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.OpsOptionsService;
import ffdd.opsconsole.platform.dto.AdminOption;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsOptionsControllerTest {
    private final OpsOptionsService optionsService = mock(OpsOptionsService.class);
    private final OpsOptionsController controller = new OpsOptionsController(optionsService);

    @Test
    void optionsDelegatesToApplicationService() {
        when(optionsService.options("devices", "datacenters"))
                .thenReturn(ApiResult.ok(List.of(AdminOption.of("香港 1 区", "HK-1"))));

        ApiResult<List<AdminOption>> result = controller.options("devices", "datacenters");

        assertThat(result.getData()).extracting(AdminOption::value).containsExactly("HK-1");
        verify(optionsService).options("devices", "datacenters");
    }
}
