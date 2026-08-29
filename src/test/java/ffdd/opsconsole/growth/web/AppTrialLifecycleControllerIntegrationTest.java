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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppTrialLifecycleControllerIntegrationTest {

    @Test
    void authenticatedUserReadsStrictServerAuthorityThroughTheHttpBoundary() {
        AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.lockTrial(42L)).thenReturn(null);
        when(mapper.policies()).thenReturn(List.of(
                new AppTrialLifecycleMapper.PolicyRow("seatsLeftToday", "47")));
        when(mapper.trialQuotaRemaining(org.mockito.ArgumentMatchers.any(LocalDate.class))).thenReturn(47);
        var product = new AppTrialLifecycleMapper.ConversionProduct(
                1L, "stellarbox-s1", "StellarBox S1", "S1", new BigDecimal("1299"),
                47, "P1", "DEVICE", "FINITE");
        when(mapper.catalogProduct("stellarbox-s1")).thenReturn(product);
        when(mapper.conversionProduct("stellarbox-s1")).thenReturn(product);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AppTrialLifecycleController controller = controller(mapper, environment);

        ApiResult<Map<String, Object>> result = controller.state(auth("42", "USER"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("authoritative", true)
                .containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("state", "ELIGIBLE")
                .containsEntry("canStart", true)
                .containsEntry("version", 0L)
                .containsKey("serverNowEpochMs");
        verify(mapper).lockTrial(42L);
    }

    @Test
    void adminSubjectCannotReadOrMutateAnotherUsersTrial() {
        AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AppTrialLifecycleController controller = controller(mapper, environment);

        ApiResult<Map<String, Object>> result = controller.state(auth("42", "ADMIN"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verifyNoInteractions(mapper);
    }

    @Test
    void developmentRuntimeUsesTheCanonicalPcManagedTrialPolicy() {
        AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.lockTrial(42L)).thenReturn(null);
        when(mapper.policies()).thenReturn(List.of(
                new AppTrialLifecycleMapper.PolicyRow("seatsLeftToday", "47")));
        when(mapper.trialQuotaRemaining(org.mockito.ArgumentMatchers.any(LocalDate.class))).thenReturn(47);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        ApiResult<Map<String, Object>> result = controller(mapper, environment).state(auth("42", "USER"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("source", "nx_trial_claim")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("state", "ELIGIBLE");
        verify(mapper).lockTrial(42L);
    }

    @Test
    void developmentRuntimeRejectsAnInactiveAccount() {
        AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        ApiResult<Map<String, Object>> result = controller(mapper, environment).state(auth("99", "USER"));

        assertThat(result.getCode()).isEqualTo(404);
        verify(mapper).activeUser(99L);
    }

    @Test
    void convertPassesTheConfirmedAmountThroughTheHttpBoundary() {
        AppTrialLifecycleService service = mock(AppTrialLifecycleService.class);
        AppTrialLifecycleController controller = new AppTrialLifecycleController(service);
        BigDecimal expectedAmount = new BigDecimal("1277.33");
        when(service.convert(42L, "stellarbox-s1", expectedAmount, "convert-key"))
                .thenReturn(ApiResult.ok(Map.of("orderNo", "TRC-1")));

        ApiResult<Map<String, Object>> result = controller.convert(
                new AppTrialLifecycleController.ConvertRequest("stellarbox-s1", expectedAmount),
                "convert-key", auth("42", "USER"));

        assertThat(result.getCode()).isZero();
        verify(service).convert(42L, "stellarbox-s1", expectedAmount, "convert-key");
    }

    private AppTrialLifecycleController controller(AppTrialLifecycleMapper mapper,
                                                   MockEnvironment environment) {
        AppTrialLifecycleService service = new AppTrialLifecycleService(
                mapper,
                mock(EarningsReleaseService.class),
                mock(AdminIdempotencyService.class),
                mock(TreasuryCoverageFacade.class),
                mock(AuditLogService.class),
                mock(EventOutboxService.class),
                environment,
                Clock.systemUTC());
        return new AppTrialLifecycleController(service);
    }

    private UsernamePasswordAuthenticationToken auth(String id, String subjectType) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(id, null, List.of());
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }
}
