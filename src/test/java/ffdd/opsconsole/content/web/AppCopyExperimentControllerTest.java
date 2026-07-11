package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.AppCopyExperimentService;
import ffdd.opsconsole.content.domain.AppCopyDeliveryView;
import ffdd.opsconsole.content.domain.AppExperimentConversionView;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppCopyExperimentControllerTest {
    private final AppCopyExperimentService service = mock(AppCopyExperimentService.class);
    private final AppCopyExperimentController controller = new AppCopyExperimentController(service);

    @Test
    void authenticatedUserReceivesServerSelectedCopy() {
        var authentication = userAuthentication("42", "USER");
        var view = new AppCopyDeliveryView("home.hero", "v3", "中", "en", "vi", "EXP-1", "B");
        when(service.deliver(42L, "home.hero")).thenReturn(ApiResult.ok(view));

        ApiResult<AppCopyDeliveryView> result = controller.deliver("home.hero", authentication);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEqualTo(view);
        verify(service).deliver(42L, "home.hero");
    }

    @Test
    void authenticatedUserReportsIdempotentConversion() {
        var authentication = userAuthentication("42", "USER");
        var view = new AppExperimentConversionView("EXP-1", "checkout:ORD-1", true);
        when(service.convert(42L, "EXP-1", "checkout:ORD-1")).thenReturn(ApiResult.ok(view));

        ApiResult<AppExperimentConversionView> result = controller.convert(
                "EXP-1", new AppExperimentConversionRequest("checkout:ORD-1"), authentication);

        assertThat(result.getData()).isEqualTo(view);
        verify(service).convert(42L, "EXP-1", "checkout:ORD-1");
    }

    @Test
    void adminSubjectCannotImpersonateAnAppUser() {
        var authentication = userAuthentication("1", "ADMIN");

        ApiResult<AppCopyDeliveryView> result = controller.deliver("home.hero", authentication);

        assertThat(result.getCode()).isEqualTo(403);
        verify(service, never()).deliver(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    private UsernamePasswordAuthenticationToken userAuthentication(String principal, String subjectType) {
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
