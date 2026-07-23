package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record E2PhoneTierConfigUpdateRequest(
        Integer tier,
        BigDecimal baseRateUsdt,
        BigDecimal baseRateNex,
        String reason,
        String operator) {
}
