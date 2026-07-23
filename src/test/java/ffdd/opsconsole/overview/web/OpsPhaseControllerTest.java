package ffdd.opsconsole.overview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.overview.application.OpsPhaseOverviewService;
import ffdd.opsconsole.overview.application.OpsPhaseOverviewService.PhaseCsvFile;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class OpsPhaseControllerTest {
    private final OpsPhaseOverviewService phaseOverviewService = mock(OpsPhaseOverviewService.class);
    private final OpsPhaseController controller = new OpsPhaseController(phaseOverviewService);

    @Test
    void exportUsesPlainAsciiFilenameWithoutRfc2047Wrapper() {
        PhaseCsvFile file = new PhaseCsvFile(
                "b4-phase-distribution-2026-07-23.csv",
                "phase,user_count,selected_phase\r\nP1,1,ALL\r\n".getBytes(StandardCharsets.UTF_8));
        when(phaseOverviewService.export("PHASE", null, null)).thenReturn(file);

        ResponseEntity<byte[]> response = controller.export("PHASE", null, null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"b4-phase-distribution-2026-07-23.csv\"");
    }
}
