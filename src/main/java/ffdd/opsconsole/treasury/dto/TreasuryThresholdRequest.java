package ffdd.opsconsole.treasury.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import ffdd.opsconsole.shared.exception.BizException;
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

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new BizException(422, "B1_THRESHOLD_REQUEST_INVALID");
    }
}
