package ffdd.opsconsole.commerce.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.commerce.application.AppStorefrontActivityService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AppStorefrontActivityControllerTest {
    private final AppStorefrontActivityService service = mock(AppStorefrontActivityService.class);
    private final AppStorefrontActivityController controller = new AppStorefrontActivityController(service);

    @Test
    void onlyVerifiedUserSubjectCanReadStorefrontFacts() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("7", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        when(service.activity(7L, "cursor", 10)).thenReturn(ApiResult.ok(Map.of("items", List.of())));

        ApiResult<Map<String, Object>> result = controller.activity("cursor", 10, authentication);

        assertThat(result.getCode()).isZero();
        verify(service).activity(7L, "cursor", 10);
    }

    @Test
    void adminSubjectCannotReadStorefrontFacts() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("1", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));

        ApiResult<Map<String, Object>> result = controller.socialProof("SKU-1", 30, authentication);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verifyNoInteractions(service);
    }
}
