package ffdd.opsconsole.bi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.bi.application.OpsBiService;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsBiControllerTest {
    private final OpsBiService biService = mock(OpsBiService.class);
    private final OpsBiController controller = new OpsBiController(biService);

    @Test
    void overviewDelegatesToService() {
        when(biService.overview()).thenReturn(ApiResult.ok(Map.of("domain", "L")));

        ApiResult<Map<String, Object>> result = controller.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "L");
    }

    @Test
    void reportActionPassesIdempotencyKey() {
        controller.reportAction("EXP-1", "approve", "idem-l", new BiReportActionRequest("approve export", "superadmin", true, false));

        verify(biService).reportAction(eq("EXP-1"), eq("approve"), eq("idem-l"), any(BiReportActionRequest.class));
    }
}
