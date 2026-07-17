package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class OpsKillSwitchAutoTriggerSchedulerTest {
    @Test
    void overdueReminderHasAnIndependentScheduleWhenSignalEvaluationFails() {
        OpsKillSwitchAutoTriggerService autoTriggerService = mock(OpsKillSwitchAutoTriggerService.class);
        OpsKillSwitchService killSwitchService = mock(OpsKillSwitchService.class);
        OpsKillSwitchAutoTriggerScheduler scheduler =
                new OpsKillSwitchAutoTriggerScheduler(autoTriggerService, killSwitchService);
        doThrow(new IllegalStateException("signal unavailable"))
                .when(autoTriggerService).evaluateAndApply();

        assertThatThrownBy(scheduler::evaluateAutoTriggers)
                .isInstanceOf(IllegalStateException.class);
        scheduler.remindOverdueConfirmations();

        verify(killSwitchService).repairAutoConfirmationOrphans();
        verify(killSwitchService).remindOverdueAutoConfirmations();
    }

    @Test
    void startupRepairsOrphanedAutoConfirmationsBeforeOperatorsCanRecoverGates() {
        OpsKillSwitchAutoTriggerService autoTriggerService = mock(OpsKillSwitchAutoTriggerService.class);
        OpsKillSwitchService killSwitchService = mock(OpsKillSwitchService.class);
        OpsKillSwitchAutoTriggerScheduler scheduler =
                new OpsKillSwitchAutoTriggerScheduler(autoTriggerService, killSwitchService);

        scheduler.repairOrphanedConfirmationsOnStartup();

        verify(killSwitchService).repairLegacyLastChangeLabels();
        verify(killSwitchService).repairAutoConfirmationOrphans();
    }
}
