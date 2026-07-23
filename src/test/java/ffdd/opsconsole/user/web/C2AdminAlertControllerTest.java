package ffdd.opsconsole.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.application.C2AdminAlertService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class C2AdminAlertControllerTest {
    private final C2AdminAlertService service = mock(C2AdminAlertService.class);
    private final AdminOperatorRoleResolver roleResolver = mock(AdminOperatorRoleResolver.class);
    private final C2AdminAlertController controller = new C2AdminAlertController(service, roleResolver);

    @Test
    void superAdminAndRiskCanReadTheFeed() {
        when(roleResolver.resolveCode()).thenReturn("RISK");
        when(service.alerts()).thenReturn(ApiResult.ok(Map.of("alerts", java.util.List.of())));

        assertThat(controller.alerts().getCode()).isZero();
        verify(service).alerts();
    }

    @Test
    void otherC2ReadersCannotReadTheHighRiskFeed() {
        when(roleResolver.resolveCode()).thenReturn("FINANCE");

        assertThat(controller.alerts().getCode()).isEqualTo(403);
        assertThat(controller.alerts().getMessage()).isEqualTo("C2_ALERTS_ROLE_FORBIDDEN");
        verifyNoInteractions(service);
    }
}
