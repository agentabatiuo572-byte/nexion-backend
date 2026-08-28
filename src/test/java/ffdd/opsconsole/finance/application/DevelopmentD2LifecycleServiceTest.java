package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.DevelopmentD2CooldownSimulationRequest;
import ffdd.opsconsole.finance.web.DevelopmentD2LifecycleController;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class DevelopmentD2LifecycleServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final LocalDateTime SIMULATED_DUE = NOW.minusSeconds(1);

    private final WithdrawalOrderRepository repository = mock(WithdrawalOrderRepository.class);
    private final OpsFinanceService financeService = mock(OpsFinanceService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditObjectLockMapper lockMapper = mock(AuditObjectLockMapper.class);
    private final DevelopmentD2LifecycleService service = new DevelopmentD2LifecycleService(
            repository, financeService, idempotency, lockMapper, CLOCK);

    @BeforeEach
    void authenticateAdminAndRunIdempotentAction() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("42", null, java.util.List.of());
        authentication.setDetails(Map.of("username", "d2-tester"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceleratesOnlyTheSelectedFutureH1HoldThenUsesCanonicalRelease() {
        WithdrawalOrderView before = mock(WithdrawalOrderView.class);
        WithdrawalOrderView after = mock(WithdrawalOrderView.class);
        when(before.withdrawalNo()).thenReturn("WD-DEV-DUE-1");
        when(before.status()).thenReturn("EXTENDED_HOLD");
        when(before.lifecycleOwner()).thenReturn("H1_PHASE_COOLDOWN");
        when(before.previousStatus()).thenReturn("REVIEW_PASSED");
        when(before.holdUntil()).thenReturn(NOW.plusDays(30));
        when(repository.lockDevelopmentH1Hold("WD-DEV-DUE-1")).thenReturn(true);
        when(repository.findByWithdrawalNo("WD-DEV-DUE-1"))
                .thenReturn(Optional.of(before), Optional.of(after));
        when(repository.accelerateDevelopmentH1Hold(
                "WD-DEV-DUE-1", "EXTENDED_HOLD", NOW.plusDays(30), SIMULATED_DUE)).thenReturn(true);
        when(financeService.releaseDueD2Lifecycle(
                "WD-DEV-DUE-1", NOW, "d2-tester", "DEVELOPMENT_SIMULATED_DUE", "开发验收模拟冷却到期"))
                .thenReturn(OpsFinanceService.D2LifecycleReleaseResult.RELEASED);

        ApiResult<WithdrawalOrderView> result = service.simulateCooldownExpiry(
                "WD-DEV-DUE-1",
                "idem-dev-due-1",
                new DevelopmentD2CooldownSimulationRequest("开发验收模拟冷却到期"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isSameAs(after);
        InOrder ordered = inOrder(repository, financeService);
        ordered.verify(repository).lockDevelopmentH1Hold("WD-DEV-DUE-1");
        ordered.verify(repository).findByWithdrawalNo("WD-DEV-DUE-1");
        ordered.verify(repository).accelerateDevelopmentH1Hold(
                "WD-DEV-DUE-1", "EXTENDED_HOLD", NOW.plusDays(30), SIMULATED_DUE);
        ordered.verify(financeService).releaseDueD2Lifecycle(
                "WD-DEV-DUE-1", NOW, "d2-tester", "DEVELOPMENT_SIMULATED_DUE", "开发验收模拟冷却到期");
        ordered.verify(repository).findByWithdrawalNo("WD-DEV-DUE-1");
    }

    @Test
    void rejectsWhenAnotherRequestHasAlreadyConsumedTheDevelopmentHold() {
        when(repository.lockDevelopmentH1Hold("WD-DEV-DUE-RACE-1")).thenReturn(false);

        assertThatThrownBy(() -> service.simulateCooldownExpiry(
                "WD-DEV-DUE-RACE-1",
                "idem-dev-due-race-1",
                new DevelopmentD2CooldownSimulationRequest("开发验收模拟冷却到期")))
                .isInstanceOf(BizException.class)
                .hasMessage("D2_DEVELOPMENT_SIMULATION_STATE_INVALID");

        verify(repository, never()).findByWithdrawalNo(anyString());
        verify(repository, never()).accelerateDevelopmentH1Hold(anyString(), anyString(), any(), any());
        verify(financeService, never()).releaseDueD2Lifecycle(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void activeA2LockFailsBeforeIdempotencyOrAnyWithdrawalMutation() {
        when(lockMapper.countActiveByTarget("D", "withdrawal", "WD-LOCKED-1")).thenReturn(1);

        assertThatThrownBy(() -> service.simulateCooldownExpiry(
                "WD-LOCKED-1",
                "idem-locked-1",
                new DevelopmentD2CooldownSimulationRequest("开发验收模拟冷却到期")))
                .isInstanceOf(BizException.class)
                .hasMessage("OBJECT_LOCKED_BY_A2");

        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any());
        verify(repository, never()).accelerateDevelopmentH1Hold(anyString(), anyString(), any(), any());
        verify(financeService, never()).releaseDueD2Lifecycle(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void a2LockCreatedAfterTheOuterCheckStillBlocksInsideTheIdempotentTransaction() {
        when(lockMapper.countActiveByTarget("D", "withdrawal", "WD-LOCKED-RACE-1"))
                .thenReturn(0, 1);

        assertThatThrownBy(() -> service.simulateCooldownExpiry(
                "WD-LOCKED-RACE-1",
                "idem-locked-race-1",
                new DevelopmentD2CooldownSimulationRequest("开发验收模拟冷却到期")))
                .isInstanceOf(BizException.class)
                .hasMessage("OBJECT_LOCKED_BY_A2");

        verify(lockMapper, org.mockito.Mockito.times(2))
                .countActiveByTarget("D", "withdrawal", "WD-LOCKED-RACE-1");
        verify(repository, never()).findByWithdrawalNo(anyString());
        verify(repository, never()).accelerateDevelopmentH1Hold(anyString(), anyString(), any(), any());
        verify(financeService, never()).releaseDueD2Lifecycle(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void springProfilesExposeTheSimulatorOnlyInDevelopment() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(WithdrawalOrderRepository.class, () -> mock(WithdrawalOrderRepository.class))
                .withBean(OpsFinanceService.class, () -> mock(OpsFinanceService.class))
                .withBean(AdminIdempotencyService.class, () -> mock(AdminIdempotencyService.class))
                .withBean(AuditObjectLockMapper.class, () -> mock(AuditObjectLockMapper.class))
                .withBean(Clock.class, () -> CLOCK)
                .withUserConfiguration(
                        DevelopmentD2LifecycleService.class,
                        DevelopmentD2LifecycleController.class);

        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DevelopmentD2LifecycleService.class)
                        .doesNotHaveBean(DevelopmentD2LifecycleController.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev", "prod"))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DevelopmentD2LifecycleService.class)
                        .doesNotHaveBean(DevelopmentD2LifecycleController.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context)
                        .hasSingleBean(DevelopmentD2LifecycleService.class)
                        .hasSingleBean(DevelopmentD2LifecycleController.class));
    }
}
