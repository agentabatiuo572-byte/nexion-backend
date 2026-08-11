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

        verify(poolService, never()).injectAndSettleCurrentWeek();
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
        verify(poolService, never()).injectAndSettleCurrentWeek();
    }

    @Test
    void normalizationUsesSpringParserInsteadOfOnlyCountingFields() {
        assertThatThrownBy(() -> LeadershipPoolSettleScheduler.normalizeCron("0 99 * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F4_SETTLE_CRON_INVALID");
    }
}
