package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppTrialLifecycleControllerIntegrationTest {

    @Test
    void authenticatedUserReadsStrictServerAuthorityThroughTheHttpBoundary() {
        AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.lockTrial(42L)).thenReturn(null);
        when(mapper.policies()).thenReturn(List.of());
        AppTrialLifecycleController controller = controller(mapper);

        ApiResult<Map<String, Object>> result = controller.state(auth("42", "USER"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("authoritative", true)
                .containsEntry("state", "ELIGIBLE")
                .containsEntry("canStart", true)
                .containsEntry("version", 0L)
                .containsKey("serverNowEpochMs");
        verify(mapper).lockTrial(42L);
    }

    @Test
    void adminSubjectCannotReadOrMutateAnotherUsersTrial() {
        AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
        AppTrialLifecycleController controller = controller(mapper);

        ApiResult<Map<String, Object>> result = controller.state(auth("42", "ADMIN"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verifyNoInteractions(mapper);
    }

    private AppTrialLifecycleController controller(AppTrialLifecycleMapper mapper) {
        AppTrialLifecycleService service = new AppTrialLifecycleService(
                mapper,
                mock(EarningsReleaseService.class),
                mock(AdminIdempotencyService.class),
                mock(TreasuryCoverageFacade.class),
                mock(AuditLogService.class),
                mock(EventOutboxService.class));
        return new AppTrialLifecycleController(service);
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(id, null, List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
