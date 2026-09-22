package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.AppRiskDisclosureService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.RequestBody;

class AppRiskDisclosureControllerTest {
    private final AppRiskDisclosureService service = mock(AppRiskDisclosureService.class);
    private final AppRiskDisclosureController controller = new AppRiskDisclosureController(service);

    @Test
    void legacyClientCanCheckGateWithoutRequestBodyAndStillFailsClosedThroughService() throws Exception {
        when(service.checkGate(42L, "withdraw", null))
                .thenReturn(ApiResult.fail(409, "RISK_DISCLOSURE_ACK_REQUIRED"));

        var result = controller.checkGate("withdraw", null, userAuthentication());

        assertThat(result.getCode()).isEqualTo(409);
        verify(service).checkGate(42L, "withdraw", null);

        Method method = AppRiskDisclosureController.class.getMethod(
                "checkGate", String.class,
                ffdd.opsconsole.content.dto.AppRiskDisclosureGateCheckRequest.class,
                org.springframework.security.core.Authentication.class);
        RequestBody annotation = method.getParameters()[1].getAnnotation(RequestBody.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.required()).isFalse();
    }

    @Test
    void anonymousReaderUsesPublicDisclosureWithoutUserIdentity() {
        when(service.publicCurrent("VN")).thenReturn(ApiResult.ok(null));

        assertThat(controller.publicCurrent("VN").getCode()).isZero();
        verify(service).publicCurrent("VN");
    }

    @Test
    void accountCurrentStillRequiresAuthenticatedUser() {
        when(service.current(null)).thenReturn(ApiResult.fail(401, "USER_AUTH_REQUIRED"));

        assertThat(controller.current(null).getCode()).isEqualTo(401);
        verify(service).current(null);
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        var authentication = new UsernamePasswordAuthenticationToken("42", null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        return authentication;
    }
}
