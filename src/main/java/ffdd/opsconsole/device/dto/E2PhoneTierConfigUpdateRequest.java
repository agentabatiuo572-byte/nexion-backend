package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record E2PhoneTierConfigUpdateRequest(
        Integer tier,
        BigDecimal baseRateUsdt,
        BigDecimal baseRateNex,
        Long expectedRevision,
        String reason,
        String operator) {
    public E2PhoneTierConfigUpdateRequest(
            Integer tier, BigDecimal baseRateUsdt, BigDecimal baseRateNex,
            String reason, String operator) {
        this(tier, baseRateUsdt, baseRateNex, 1L, reason, operator);
    }
}
