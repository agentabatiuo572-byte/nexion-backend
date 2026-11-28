package ffdd.opsconsole.treasury.facade;

import java.math.BigDecimal;

public record TreasuryEmergencySignalSnapshot(
        BigDecimal bankRunRatioPct,
        BigDecimal reconciliationGapUsdt,
        BigDecimal bankRunRedlinePct,
        boolean bankRunRedlineReached) {

    public TreasuryEmergencySignalSnapshot(
            BigDecimal bankRunRatioPct, BigDecimal reconciliationGapUsdt, BigDecimal bankRunRedlinePct) {
        this(bankRunRatioPct, reconciliationGapUsdt, bankRunRedlinePct,
                bankRunRatioPct.compareTo(bankRunRedlinePct) >= 0);
    }
}
