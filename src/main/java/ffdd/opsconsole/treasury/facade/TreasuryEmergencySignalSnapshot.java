package ffdd.opsconsole.treasury.facade;

import java.math.BigDecimal;

public record TreasuryEmergencySignalSnapshot(
        BigDecimal bankRunRatioPct,
        BigDecimal reconciliationGapUsdt,
        BigDecimal bankRunRedlinePct) {
}
