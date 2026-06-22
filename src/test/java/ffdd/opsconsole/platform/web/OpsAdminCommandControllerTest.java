package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.OpsAdminCommandService;
import ffdd.opsconsole.platform.dto.AdminCommandRequest;
import ffdd.opsconsole.platform.dto.AdminCommandResponse;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsAdminCommandControllerTest {
    private final OpsAdminCommandService commandService = mock(OpsAdminCommandService.class);
    private final OpsAdminCommandController controller = new OpsAdminCommandController(commandService);

    @Test
    void acceptDelegatesWithIdempotencyHeader() {
        AdminCommandRequest request = new AdminCommandRequest("E", "删除 SKU", "SKU", "sku-1", "superadmin", "cleanup", null, null, Map.of());
        AdminCommandResponse response = new AdminCommandResponse("cmd-1", "删除 SKU", "SKU", "sku-1", false, LocalDateTime.now());
        when(commandService.accept("idem-1", request)).thenReturn(ApiResult.ok(response));

        ApiResult<AdminCommandResponse> result = controller.accept("idem-1", request);

        assertThat(result.getData()).isSameAs(response);
        verify(commandService).accept("idem-1", request);
    }
}
