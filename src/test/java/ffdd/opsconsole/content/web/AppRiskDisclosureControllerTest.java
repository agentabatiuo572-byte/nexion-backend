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

    /**
     * zentao #226:服务条款页（公开）里的「另见:平台风险披露」必须能在**未登录**时读到。
     * 未登录 → `Authentication` 为 null → 控制器不得据此拒绝,而应把 null 用户交给服务层,
     * 由服务层只发布公开版本(有会话时才附加该用户的确认状态)。
     */
    @Test
    void anAnonymousReaderIsServedThePublishedDisclosureRatherThanRejected() {
        when(service.current(null)).thenReturn(ApiResult.ok(null));

        var result = controller.current(null);

        assertThat(result.getCode()).isEqualTo(0);
        verify(service).current(null);
    }

    /** 匿名 token(不是「没有 token」)同样只能拿到公开版本,不能被当成某个用户。 */
    @Test
    void anAnonymousAuthenticationTokenIsNotProjectedIntoAUserIdentity() {
        when(service.current(null)).thenReturn(ApiResult.ok(null));
        var anonymous = new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "fixture", "anonymousUser",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(controller.current(anonymous).getCode()).isEqualTo(0);
        verify(service).current(null);
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        var authentication = new UsernamePasswordAuthenticationToken("42", null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        return authentication;
    }
}
