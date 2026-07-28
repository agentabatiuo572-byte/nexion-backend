package ffdd.opsconsole.team.dto;

import java.math.BigDecimal;

public record F5CommissionAnomalyConfigRequest(
        BigDecimal commissionAnomalySigma,
        BigDecimal layerRatioAnomalyPct,
        String reason,
        String operator) {
}
