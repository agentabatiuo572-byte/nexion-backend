package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LeadershipPoolSettleSchedulerTest {

    @Test
    void canonicalMondayCronUsesInjectedClockAndOnlyTheClosedWeekEntryPoint() {
        var pool=mock(LeadershipPoolService.class);
        var guard=mock(LeadershipPoolConfigGuard.class);
        when(guard.requireValid()).thenReturn(new LeadershipPoolConfigGuard.SettlementConfig(
                1,java.math.BigDecimal.ONE,3,new java.math.BigDecimal("5000"),"0 0 0 * * MON","fixture"));
        var alert=mock(LeadershipPoolConfigAlertService.class);
        var before=new LeadershipPoolSettleScheduler(pool,guard,alert,java.time.Clock.fixed(
                java.time.Instant.parse("2026-10-18T16:59:00Z"),java.time.ZoneOffset.UTC));
        before.weeklySettle();
        org.mockito.Mockito.verifyNoInteractions(pool);
        var boundary=java.time.Instant.parse("2026-10-18T17:00:00Z");
        var after=new LeadershipPoolSettleScheduler(pool,guard,alert,java.time.Clock.fixed(boundary,java.time.ZoneOffset.UTC));
        after.weeklySettle();
        after.weeklySettle();
        verify(pool).injectAndSettleLastClosedWeek(boundary);
        verify(pool,never()).injectAndSettleCurrentWeek();
    }

    @Test
    void invalidPersistedCronFailsSafeWithoutInvokingSettlement() {
        LeadershipPoolService poolService = mock(LeadershipPoolService.class);
        PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
        when(configFacade.activeValue("team.ui.F.pool.configVersion")).thenReturn(Optional.of("1"));
        when(configFacade.activeValue("team.ui.F.pool.ratio")).thenReturn(Optional.of("5%"));
        when(configFacade.activeValue("team.ui.F.pool.unlockVRank")).thenReturn(Optional.of("V3"));
        when(configFacade.activeValue("team.ui.F.pool.monthlyCap")).thenReturn(Optional.of("5000"));
        when(configFacade.activeValue("team.ui.F.pool.settleCron"))
                .thenReturn(Optional.of("persisted-invalid-value"));
        LeadershipPoolConfigAlertService alertService = mock(LeadershipPoolConfigAlertService.class);

        new LeadershipPoolSettleScheduler(
                poolService, new LeadershipPoolConfigGuard(configFacade), alertService).weeklySettle();

        org.mockito.Mockito.verifyNoInteractions(poolService);
    }

    @Test
    void repeatsOfTheSameInvalidConfigurationEmitOneDeduplicatedAlertAndNeverSettle() {
        LeadershipPoolService poolService = mock(LeadershipPoolService.class);
        PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
        when(configFacade.activeValue("team.ui.F.pool.configVersion")).thenReturn(Optional.of("1"));
        when(configFacade.activeValue("team.ui.F.pool.ratio")).thenReturn(Optional.of("5%"));
        when(configFacade.activeValue("team.ui.F.pool.unlockVRank")).thenReturn(Optional.of("V3"));
        when(configFacade.activeValue("team.ui.F.pool.monthlyCap")).thenReturn(Optional.of("5000"));
        when(configFacade.activeValue("team.ui.F.pool.settleCron"))
                .thenReturn(Optional.of("persisted-invalid-value"));
        LeadershipPoolConfigAlertService alertService = mock(LeadershipPoolConfigAlertService.class);
        LeadershipPoolSettleScheduler scheduler = new LeadershipPoolSettleScheduler(
                poolService, new LeadershipPoolConfigGuard(configFacade), alertService);

        scheduler.weeklySettle();
        scheduler.weeklySettle();

        verify(alertService, times(1)).recordBlocked(
                org.mockito.ArgumentMatchers.any(LeadershipPoolConfigGuard.ConfigUnavailableException.class),
                org.mockito.ArgumentMatchers.eq("scheduler"));
        org.mockito.Mockito.verifyNoInteractions(poolService);
    }

    @Test
    void normalizationUsesSpringParserInsteadOfOnlyCountingFields() {
        assertThatThrownBy(() -> LeadershipPoolSettleScheduler.normalizeCron("0 99 * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F4_SETTLE_CRON_INVALID");
    }
}
