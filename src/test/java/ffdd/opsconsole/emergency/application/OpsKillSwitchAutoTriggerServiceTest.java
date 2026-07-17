package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalFacade;
import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalSnapshot;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OpsKillSwitchAutoTriggerServiceTest {
    private final TreasuryEmergencySignalFacade signalFacade = mock(TreasuryEmergencySignalFacade.class);
    private final OpsKillSwitchService killSwitchService = mock(OpsKillSwitchService.class);
    private final OpsKillSwitchAutoTriggerService service =
            new OpsKillSwitchAutoTriggerService(signalFacade, killSwitchService);

    @Test
    void evaluatesRealTreasurySignalsAndDisablesTheMappedGates() {
        when(signalFacade.snapshot()).thenReturn(new TreasuryEmergencySignalSnapshot(
                new BigDecimal("45"), new BigDecimal("75000"), new BigDecimal("40")));
        when(killSwitchService.maturityGapThresholdUsdt()).thenReturn(new BigDecimal("50000"));
        when(killSwitchService.autoDisable("withdraw", "withdrawSurge", new BigDecimal("45"), new BigDecimal("40")))
                .thenReturn(true);
        when(killSwitchService.autoDisable("exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000")))
                .thenReturn(true);

        var result = service.evaluateAndApply();

        assertThat(result).containsEntry("withdrawTriggered", true).containsEntry("exchangeTriggered", true);
        verify(killSwitchService).autoDisable(
                "withdraw", "withdrawSurge", new BigDecimal("45"), new BigDecimal("40"));
        verify(killSwitchService).autoDisable(
                "exchange", "maturityGap", new BigDecimal("75000"), new BigDecimal("50000"));
    }

    @Test
    void belowThresholdSignalsDoNotChangeAnyGate() {
        when(signalFacade.snapshot()).thenReturn(new TreasuryEmergencySignalSnapshot(
                new BigDecimal("20"), new BigDecimal("100"), new BigDecimal("40")));
        when(killSwitchService.maturityGapThresholdUsdt()).thenReturn(new BigDecimal("50000"));

        var result = service.evaluateAndApply();

        assertThat(result).containsEntry("withdrawTriggered", false).containsEntry("exchangeTriggered", false);
    }

    @Test
    void usesTheCurrentB5BankRunRedlineFromTheSameSignalSnapshot() {
        when(signalFacade.snapshot()).thenReturn(new TreasuryEmergencySignalSnapshot(
                new BigDecimal("45"), BigDecimal.ZERO, new BigDecimal("50")));
        when(killSwitchService.maturityGapThresholdUsdt()).thenReturn(new BigDecimal("50000"));

        var result = service.evaluateAndApply();

        assertThat(result)
                .containsEntry("bankRunRedlinePct", new BigDecimal("50"))
                .containsEntry("withdrawTriggered", false);
    }

    @Test
    void reachingTheB5RedlineTriggersTheSameJ1R1BoundaryShownByB5() {
        when(signalFacade.snapshot()).thenReturn(new TreasuryEmergencySignalSnapshot(
                new BigDecimal("40"), BigDecimal.ZERO, new BigDecimal("40")));
        when(killSwitchService.maturityGapThresholdUsdt()).thenReturn(new BigDecimal("50000"));
        when(killSwitchService.autoDisable(
                "withdraw", "withdrawSurge", new BigDecimal("40"), new BigDecimal("40")))
                .thenReturn(true);

        var result = service.evaluateAndApply();

        assertThat(result).containsEntry("withdrawTriggered", true);
        verify(killSwitchService).autoDisable(
                "withdraw", "withdrawSurge", new BigDecimal("40"), new BigDecimal("40"));
    }
}
