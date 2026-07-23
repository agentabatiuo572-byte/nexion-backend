package ffdd.opsconsole.treasury.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TreasuryEmergencySignalFacadeAdapterTest {
    private final TreasuryLedgerRepository repository = mock(TreasuryLedgerRepository.class);
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final OpsTreasuryService treasuryService = mock(OpsTreasuryService.class);
    private final TreasuryEmergencySignalFacadeAdapter facade =
            new TreasuryEmergencySignalFacadeAdapter(repository, configFacade, treasuryService);

    @Test
    void snapshotUsesSliding24HourWithdrawalRequestsAndLedgerWalletReconciliationGap() {
        when(treasuryService.reserve()).thenReturn(ApiResult.ok(Map.of("reserveTotalUsdt", new BigDecimal("100000"))));
        when(repository.sumWithdrawalRequested24hUsdt()).thenReturn(new BigDecimal("45000"));
        when(repository.sumActiveWithdrawalQueueUsdt()).thenReturn(new BigDecimal("1"));
        when(repository.walletLedgerReconciliationGapUsdt()).thenReturn(new BigDecimal("75000"));
        when(configFacade.activeValue(TreasuryEmergencySignalFacadeAdapter.BANK_RUN_REDLINE_CONFIG_KEY))
                .thenReturn(Optional.of("55"));

        var snapshot = facade.snapshot();

        assertThat(snapshot.bankRunRatioPct()).isEqualByComparingTo("45");
        assertThat(snapshot.reconciliationGapUsdt()).isEqualByComparingTo("75000");
        assertThat(snapshot.bankRunRedlinePct()).isEqualByComparingTo("55");
    }

    @Test
    void invalidBandOrderingFallsBackToTheSameDefaultsUsedByB5() {
        when(configFacade.activeValue(BankRunThresholdPolicy.YELLOW_CONFIG_KEY))
                .thenReturn(Optional.of("50"));
        when(configFacade.activeValue(BankRunThresholdPolicy.REDLINE_CONFIG_KEY))
                .thenReturn(Optional.of("45"));

        assertThat(facade.bankRunRedlinePct()).isEqualByComparingTo("40");
    }
}
