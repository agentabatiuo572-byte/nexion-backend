package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record E2YieldComparisonUpdateRequest(
        String configKey,
        String label,
        BigDecimal dailyUsdt,
        BigDecimal dailyNex,
        Long expectedRevision,
        String reason,
        String operator) {
    public E2YieldComparisonUpdateRequest(
            String configKey, String label, BigDecimal dailyUsdt, BigDecimal dailyNex,
            String reason, String operator) {
        this(configKey, label, dailyUsdt, dailyNex, 1L, reason, operator);
    }
}
