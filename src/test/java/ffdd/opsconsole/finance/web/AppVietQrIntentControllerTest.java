package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.application.AppVietQrIntentService;
import ffdd.opsconsole.finance.dto.AppVietQrIntentCreateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AppVietQrIntentControllerTest {
    private final AppVietQrIntentService service = mock(AppVietQrIntentService.class);
    private final AppVietQrIntentController controller = new AppVietQrIntentController(service);

    @Test
    void authenticatedUserSubjectOwnsCreateRequestIdentity() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("41", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        when(service.create(41L, "app-vietqr:create:key-1", new BigDecimal("25")))
                .thenReturn(ApiResult.ok(Map.of("intentNo", "VQR-1")));

        ApiResult<Map<String, Object>> result = controller.create(
                "app-vietqr:create:key-1",
                new AppVietQrIntentCreateRequest(new BigDecimal("25")),
                authentication);

        assertThat(result.getData()).containsEntry("intentNo", "VQR-1");
        verify(service).create(41L, "app-vietqr:create:key-1", new BigDecimal("25"));
    }

    @Test
    void adminSubjectCannotUseAppIntentEndpoints() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("1", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));

        ApiResult<Map<String, Object>> result = controller.create(
                "app-vietqr:create:key-2",
                new AppVietQrIntentCreateRequest(new BigDecimal("25")),
                authentication);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verify(service, never()).create(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
