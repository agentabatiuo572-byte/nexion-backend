package ffdd.opsconsole.treasury.dto;

import java.math.BigDecimal;

public record TreasuryThresholdRequest(
        BigDecimal redlinePct,
        BigDecimal healthyPct,
        BigDecimal runRiskPct,
        String reason,
        String operator,
        Long expectedVersion) {
    public TreasuryThresholdRequest(
            BigDecimal redlinePct, BigDecimal healthyPct, BigDecimal runRiskPct, String reason, String operator) {
        this(redlinePct, healthyPct, runRiskPct, reason, operator, null);
    }
}
