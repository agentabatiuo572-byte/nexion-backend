package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.application.AuditReplayBusinessPermissionGuard;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class F4PausedPayoutTest {
    private static final String PAUSED = "team.ui.F.leaderboard.paused";
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final TeamCommissionMapper mapper = mock(TeamCommissionMapper.class);
    private final TeamCommissionRepository commissions = mock(TeamCommissionRepository.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final LeadershipPoolService service = new LeadershipPoolService(mapper, commissions, ledger, config,
            audit, outbox, new LeadershipPoolConfigGuard(config), mock(LeadershipPoolConfigAlertService.class));
    private final AuditReplayBusinessPermissionGuard guard = new AuditReplayBusinessPermissionGuard(
            mock(TrustDisclosureRepository.class), mock(AdminOperatorRoleResolver.class),
            mock(EmergencyControlRepository.class), null, config);
    private final AuditReplayCommand command = new AuditReplayCommand(
            "F", "f4_leaderboard_period_payout", Map.of("period", "allTime"));

    @AfterEach void cleanup() {
        A2ReplayContext.exitReplay();
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String... codes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("51", null,
                java.util.Arrays.stream(codes).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test void genericA2AndOtherF4PermissionsCannotProposeOrApprovePayout() {
        for (String scope : List.of("network_f4_read", "network_f4_write", "network_f4_leaderboard_control")) {
            authenticate("platform_a2_write", "platform_a2_operation_approve", scope);
            assertThat(guard.validateProposal(command).getCode()).isEqualTo(403);
            assertThat(guard.validateApproval(command).getMessage())
                    .isEqualTo("A2_BUSINESS_PERMISSION_DENIED:network_f4_pool_fund");
        }
        verifyNoInteractions(config);
    }

    @Test void pausedAuthorizedProposalAndApprovalRejectBeforeDomainReplay() {
        authenticate("platform_a2_write", "platform_a2_operation_approve", "network_f4_pool_fund");
        for (String paused : List.of("on", " TRUE ", "1", "paused")) {
            when(config.activeValue(PAUSED)).thenReturn(Optional.of(paused));
            assertThat(guard.validateProposal(command).getCode()).isEqualTo(409);
            assertThat(guard.validateApproval(command).getMessage()).isEqualTo("F4_LEADERBOARD_PAUSED");
        }
        verifyNoInteractions(commissions, ledger, outbox, audit, mapper);
    }

    @Test void unpausedAuthorizedProposalAndApprovalRetainNormalContract() {
        authenticate("platform_a2_write", "platform_a2_operation_approve", "network_f4_pool_fund");
        when(config.activeValue(PAUSED)).thenReturn(Optional.of("off"));
        assertThat(guard.validateProposal(command).getCode()).isZero();
        assertThat(guard.validateApproval(command).getCode()).isZero();
    }

    @Test void adminExecutionRechecksCurrentPauseBeforeFinancialOrSettlementWrites() {
        // A stale ordinary read says off; the current locked read must win.
        when(config.activeValue(PAUSED)).thenReturn(Optional.of("off"));
        when(config.activeValueForUpdate(PAUSED)).thenReturn(Optional.of("on"));
        when(mapper.lockLeadershipSettlementMutex(anyInt())).thenReturn(1L);
        A2ReplayContext.enterReplay("fixture-f4-payout");
        for (var period : Map.of("today", "2026-01-01", "week", "2026-01-05", "month", "2026-01", "allTime", "").entrySet()) {
            assertThatThrownBy(() -> service.settleApprovedLeaderboardPeriod(
                    period.getKey(), period.getValue(), "checker", "fixture pause guard"))
                    .isInstanceOfSatisfying(BizException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(409);
                        assertThat(ex.getMessage()).isEqualTo("F4_LEADERBOARD_PAUSED");
                    });
        }
        verifyNoInteractions(commissions, ledger, outbox, audit);
        verify(mapper, never()).countLeaderboardBySettlementKey(anyString());
        verify(config, never()).upsertAdminValue(any(), any(), any(), any(), any());
    }

    @Test void unpausedAdminExecutionRetainsOriginalCapAndLifetimeSettlementKey() {
        when(config.activeValueForUpdate(PAUSED)).thenReturn(Optional.of("off"));
        when(config.activeValue("team.ui.F.pool.periodPrize"))
                .thenReturn(Optional.of("{\"allTime\":100}"));
        when(mapper.lockLeadershipSettlementMutex(anyInt())).thenReturn(1L);
        when(commissions.leaderboardCandidates(eq("allTime"), isNull(), any(), eq(BigDecimal.ZERO), eq(100)))
                .thenReturn(List.of(Map.of("userId", 17L)));
        when(commissions.insertCommissionEvent(anyLong(), anyString(), isNull(), anyString(), any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(71L);
        A2ReplayContext.enterReplay("fixture-f4-unpaused");
        assertThat(service.settleApprovedLeaderboardPeriod("allTime", "checker", "fixture ordinary payout")).isEqualTo(1);
        verify(commissions).insertCommissionEvent(eq(17L), eq("leaderboard_prize"), isNull(), eq("USDT"),
                eq(new BigDecimal("25.00")), any(), eq("UNLOCKED"), eq(0), contains("settlementKey=allTime:lifetime"));
        verify(ledger).releaseCommissionFunds(71L);
        verify(outbox).publish(eq("LEADERBOARD_PRIZE"), eq("F4-LB-allTime:lifetime-71"), eq("commission.paid"), any());
        verify(audit).recordRequired(any());
        var order = inOrder(mapper, config);
        order.verify(mapper).ensureLeadershipSettlementMutex(-4);
        order.verify(mapper).lockLeadershipSettlementMutex(-4);
        order.verify(config).activeValueForUpdate(PAUSED);
        order.verify(mapper).countLeaderboardBySettlementKey("allTime:lifetime");
        order.verify(mapper).ensureLeadershipSettlementMutex("allTime:lifetime".hashCode() & Integer.MAX_VALUE);
    }

    @Test void adminRequiresA2BeforeAnyConfigurationOrSettlementAccess() {
        assertThatThrownBy(() -> service.settleApprovedLeaderboardPeriod("allTime", "checker", "fixture direct call"))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
                });
        verifyNoInteractions(config, mapper, commissions, ledger, outbox, audit);
    }

    @Test void maintenanceLockFailureNeverFallsThroughToPayout() {
        when(mapper.lockLeadershipSettlementMutex(-4)).thenReturn(null);
        A2ReplayContext.enterReplay("fixture-lock-unavailable");
        assertThatThrownBy(() -> service.settleApprovedLeaderboardPeriod("allTime", "checker", "fixture lock failure"))
                .isInstanceOf(IllegalStateException.class).hasMessage("F4_LEADERBOARD_MUTEX_UNAVAILABLE");
        verifyNoInteractions(config, commissions, ledger, outbox, audit);
    }

    @Test void waitingAdminReadsPauseAfterMaintenanceLockInsteadOfPriorOrdinaryRead() throws Exception {
        // RAM lock models transaction release only; this is not a MySQL isolation test.
        var maintenance = new java.util.concurrent.locks.ReentrantLock();
        var attempted = new java.util.concurrent.CountDownLatch(1);
        var currentPaused = new java.util.concurrent.atomic.AtomicBoolean(false);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        when(config.activeValue(PAUSED)).thenReturn(Optional.of("off"));
        when(config.activeValueForUpdate(PAUSED)).thenAnswer(i -> Optional.of(currentPaused.get() ? "on" : "off"));
        when(mapper.lockLeadershipSettlementMutex(-4)).thenAnswer(i -> {
            attempted.countDown();
            maintenance.lock();
            return 1L;
        });
        maintenance.lock();
        try {
            var payout = executor.submit(() -> {
                A2ReplayContext.enterReplay("fixture-waiting-admin");
                try {
                    service.settleApprovedLeaderboardPeriod("allTime", "checker", "fixture concurrent pause");
                    return 0;
                } catch (BizException rejected) {
                    assertThat(rejected.getMessage()).isEqualTo("F4_LEADERBOARD_PAUSED");
                    return rejected.getCode();
                } finally {
                    A2ReplayContext.exitReplay();
                    if (maintenance.isHeldByCurrentThread()) maintenance.unlock();
                }
            });
            assertThat(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(payout.isDone()).isFalse();
            currentPaused.set(true);
            maintenance.unlock();
            assertThat(payout.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(409);
            verify(config).activeValueForUpdate(PAUSED);
            verify(config, never()).activeValue(PAUSED);
            verifyNoInteractions(commissions, ledger, outbox, audit);
        } finally {
            if (maintenance.isHeldByCurrentThread()) maintenance.unlock();
            executor.shutdownNow();
        }
    }

    @Test void systemPausedSchedulerRetainsZeroPayoutAndExistingCheckpointAdvance() {
        when(config.activeValue(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case PAUSED -> Optional.of("on");
            case "team.runtime.F.leaderboard.lastSettled.today" -> Optional.of("2026-08-08");
            case "team.runtime.F.leaderboard.lastSettled.week" -> Optional.of("2026-08-03");
            case "team.runtime.F.leaderboard.lastSettled.month" -> Optional.of("2026-07");
            default -> Optional.empty();
        });
        assertThat(service.settleClosedLeaderboardPeriods(
                ZonedDateTime.of(2026, 8, 10, 0, 37, 0, 0, ZoneOffset.UTC))).isZero();
        verify(config).upsertAdminValue("team.runtime.F.leaderboard.lastSettled.today", "2026-08-09",
                "TEXT", "team_runtime", "F16 leaderboard settlement checkpoint");
        verify(config, never()).activeValueForUpdate(PAUSED);
        verifyNoInteractions(commissions, ledger, outbox, audit, mapper);
    }
}
