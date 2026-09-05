package ffdd.opsconsole.developer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.home.application.AppHomeOverviewService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class DeveloperOpenApiControllerTest {
    @Test
    void apiKeySubjectCanReadOnlyItsOwnCanonicalHomeOverview() {
        var service = mock(AppHomeOverviewService.class);
        when(service.overview(7L)).thenReturn(ApiResult.ok(Map.of("source", "server")));
        var authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "DEVELOPER_API_KEY", "keyId", "dak_1"));

        var result = new DeveloperOpenApiController(service).homeOverview(authentication);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "server");
        verify(service).overview(7L);
    }

    @Test
    void ordinarySessionCannotBeReusedAsADeveloperKey() {
        var service = mock(AppHomeOverviewService.class);
        var authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));

        var result = new DeveloperOpenApiController(service).homeOverview(authentication);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_API_KEY_REQUIRED");
    }
}
