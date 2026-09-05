package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.hdpay.HdPayHostedDepositService;
import ffdd.opsconsole.finance.dto.AppVietQrIntentCreateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AppVietQrIntentControllerTest {
    private final HdPayHostedDepositService service = mock(HdPayHostedDepositService.class);
    private final GatewaySecurityProperties gatewaySecurity = new GatewaySecurityProperties();
    private final AppVietQrIntentController controller =
            new AppVietQrIntentController(service, gatewaySecurity);

    @Test
    void authenticatedUserSubjectOwnsCreateRequestIdentity() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("41", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        when(service.create(41L, "app-vietqr:create:key-1", new BigDecimal("25"), "203.0.113.9"))
                .thenReturn(ApiResult.ok(Map.of("intentNo", "VQR-1")));

        ApiResult<Map<String, Object>> result = controller.create(
                "app-vietqr:create:key-1",
                new AppVietQrIntentCreateRequest(new BigDecimal("25")),
                authentication,
                request);

        assertThat(result.getData()).containsEntry("intentNo", "VQR-1");
        verify(service).create(41L, "app-vietqr:create:key-1", new BigDecimal("25"), "203.0.113.9");
    }

    @Test
    void adminSubjectCannotUseAppIntentEndpoints() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("1", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));

        ApiResult<Map<String, Object>> result = controller.create(
                "app-vietqr:create:key-2",
                new AppVietQrIntentCreateRequest(new BigDecimal("25")),
                authentication,
                null);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verify(service, never()).create(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
