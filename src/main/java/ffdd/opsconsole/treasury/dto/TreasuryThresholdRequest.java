package ffdd.opsconsole.treasury.dto;

import java.math.BigDecimal;

public record TreasuryThresholdRequest(
        BigDecimal redlinePct,
        BigDecimal healthyPct,
        BigDecimal runRiskPct,
        String reason,
        String operator) {
}
