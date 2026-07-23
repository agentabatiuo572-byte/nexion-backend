package ffdd.opsconsole.auth.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.application.AppUserSecurityService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppUserSecurityControllerTest {
    private final AppUserSecurityService service = mock(AppUserSecurityService.class);
    private final AppUserSecurityController controller = new AppUserSecurityController(service);

    @Test
    void authenticatedUserIdentityAndSessionComeOnlyFromTheValidatedJwt() {
        var authentication = new UsernamePasswordAuthenticationToken("42", null, java.util.List.of());
        authentication.setDetails(Map.of("subjectType", "USER", "sessionId", "current-session"));

        controller.overview(authentication);

        verify(service).overview(42L, "current-session");
    }

    @Test
    void adminOrGatewayIdentityWithoutUserSessionCannotCallSensitiveEndpoints() {
        var admin = new UsernamePasswordAuthenticationToken("1", null, java.util.List.of());
        admin.setDetails(Map.of("subjectType", "ADMIN", "sessionId", "admin-session"));
        var headerOnlyUser = new UsernamePasswordAuthenticationToken("42", null, java.util.List.of());
        headerOnlyUser.setDetails(Map.of("subjectType", "USER"));

        assertThatThrownBy(() -> controller.overview(admin)).hasMessage("USER_AUTH_REQUIRED");
        assertThatThrownBy(() -> controller.overview(headerOnlyUser)).hasMessage("USER_AUTH_REQUIRED");
    }
}
