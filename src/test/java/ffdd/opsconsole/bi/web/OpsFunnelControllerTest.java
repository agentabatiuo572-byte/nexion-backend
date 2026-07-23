package ffdd.opsconsole.bi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.application.OpsFunnelService;
import ffdd.opsconsole.bi.application.OpsFunnelService.FunnelCsvFile;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class OpsFunnelControllerTest {

    @Test
    void exportUsesBrowserFriendlyAsciiFileNameWithoutEncodedWordWrapper() {
        OpsFunnelService service = mock(OpsFunnelService.class);
        when(service.export(null, null, null)).thenReturn(new FunnelCsvFile(
                "b3-funnel-2026-07-23.csv",
                "stage\r\n".getBytes(StandardCharsets.UTF_8)));
        OpsFunnelController controller = new OpsFunnelController(service);

        ResponseEntity<byte[]> response = controller.export(null, null, null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"b3-funnel-2026-07-23.csv\"");
    }
}
