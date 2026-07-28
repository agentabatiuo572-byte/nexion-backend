package ffdd.opsconsole.bi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.bi.application.OpsBiService;
import ffdd.opsconsole.bi.domain.BiReportStreamDownload;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OpsBiControllerTest {
    private final OpsBiService biService = mock(OpsBiService.class);
    private final OpsBiController controller = new OpsBiController(biService, new ObjectMapper());

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

    @Test
    void reportsReturnPageResult() {
        when(biService.reports(any(BiReportQueryRequest.class))).thenReturn(ApiResult.ok(new PageResult<>(0, 1, 20, List.of())));

        var result = controller.reports(new BiReportQueryRequest(null, "ALL", 1, 20, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isZero();
        verify(biService).reports(any(BiReportQueryRequest.class));
    }

    @Test
    void reportDownloadStreamsBodyAndCompletesAuditCallback() throws Exception {
        AtomicBoolean completed = new AtomicBoolean();
        byte[] csv = "header\nvalue\n".getBytes(StandardCharsets.UTF_8);
        when(biService.downloadStreamFile("EXP-1", "token")).thenReturn(ApiResult.ok(
                new BiReportStreamDownload(
                        "exp-1.csv",
                        "text/csv;charset=UTF-8",
                        csv.length,
                        new ByteArrayInputStream(csv),
                        () -> completed.set(true))));

        var response = controller.downloadFile("EXP-1", "token");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(output.toByteArray()).isEqualTo(csv);
        assertThat(completed).isTrue();
    }

    @Test
    void reportDownloadKeepsStructuredHttpError() throws Exception {
        when(biService.downloadStreamFile("EXP-1", "forged"))
                .thenReturn(ApiResult.fail(403, "DOWNLOAD_TOKEN_INVALID_OR_EXPIRED"));

        var response = controller.downloadFile("EXP-1", "forged");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("\"code\":403", "\"message\":\"DOWNLOAD_TOKEN_INVALID_OR_EXPIRED\"");
    }
}
