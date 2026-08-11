package ffdd.opsconsole.team.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.team.application.F5CommissionService;
import ffdd.opsconsole.team.dto.F5CommissionExportPayload;
import ffdd.opsconsole.team.dto.F5CommissionExportRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class F5CommissionControllerTest {
    @Test
    void exportUsesDynamicA2ReasonPolicyAuthenticatedSubjectAndPreservesBinaryHeaders() {
        F5CommissionService service = mock(F5CommissionService.class);
        A2RuntimePolicy a2RuntimePolicy = mock(A2RuntimePolicy.class);
        F5CommissionController controller = new F5CommissionController(service, a2RuntimePolicy);
        F5CommissionExportRequest request = new F5CommissionExportRequest(
                "network", "USDT", null, "unlocked", "2026-08", "动态策略导出理由验收");
        byte[] content = "\ufeffcommissionId\r\nCM-1\r\n".getBytes(StandardCharsets.UTF_8);
        F5CommissionExportPayload payload = new F5CommissionExportPayload(
                "F5-CSV-test", "f5-commissions-20260811-000000.csv", 1L, content.length,
                "f".repeat(64), content);
        when(service.export(eq("idem-f19"), eq(request), eq("export-auditor"))).thenReturn(payload);

        ResponseEntity<byte[]> response = controller.export(
                "idem-f19", request,
                new UsernamePasswordAuthenticationToken("export-auditor", null, List.of()));

        verify(a2RuntimePolicy).validateReason("动态策略导出理由验收");
        verify(service).export("idem-f19", request, "export-auditor");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("attachment", "f5-commissions-20260811-000000.csv");
        assertThat(response.getHeaders().getFirst("X-Export-Id")).isEqualTo("F5-CSV-test");
        assertThat(response.getHeaders().getFirst("X-Export-Row-Count")).isEqualTo("1");
        assertThat(response.getHeaders().getFirst("X-Export-Byte-Size")).isEqualTo(String.valueOf(content.length));
        assertThat(response.getHeaders().getFirst("X-Export-Sha256")).isEqualTo("f".repeat(64));
        assertThat(response.getHeaders().getFirst("X-Export-Redacted")).isEqualTo("true");
        assertThat(response.getBody()).containsExactly(content);
    }
}
