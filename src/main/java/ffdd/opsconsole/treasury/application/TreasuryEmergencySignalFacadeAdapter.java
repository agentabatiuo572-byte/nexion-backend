package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalFacade;
import ffdd.opsconsole.treasury.facade.TreasuryEmergencySignalSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TreasuryEmergencySignalFacadeAdapter implements TreasuryEmergencySignalFacade {
    public static final String BANK_RUN_REDLINE_CONFIG_KEY = BankRunThresholdPolicy.REDLINE_CONFIG_KEY;

    private final TreasuryLedgerRepository ledgerRepository;
    private final PlatformConfigFacade configFacade;

    @Override
    public TreasuryEmergencySignalSnapshot snapshot() {
        BigDecimal reserve = safe(ledgerRepository.currentReserveUsd());
        BigDecimal withdrawalRequests24h = safe(ledgerRepository.sumWithdrawalRequested24hUsdt());
        BigDecimal bankRunRatio = reserve.compareTo(BigDecimal.ZERO) > 0
                ? withdrawalRequests24h.multiply(new BigDecimal("100"))
                .divide(reserve, 4, RoundingMode.HALF_UP).stripTrailingZeros()
                : withdrawalRequests24h.compareTo(BigDecimal.ZERO) > 0 ? new BigDecimal("100") : BigDecimal.ZERO;
        return new TreasuryEmergencySignalSnapshot(
                bankRunRatio,
                safe(ledgerRepository.walletLedgerReconciliationGapUsdt()).stripTrailingZeros(),
                bankRunRedlinePct());
    }

    @Override
    public BigDecimal bankRunRedlinePct() {
        return BankRunThresholdPolicy.resolve(configFacade).redlinePct();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }
}
