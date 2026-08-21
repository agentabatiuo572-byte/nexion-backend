package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DevicePhoneTierRewardUpdateRequest(
        BigDecimal dailyUsdt,
        BigDecimal dailyNex,
        Long expectedRevision,
        String reason,
        String operator) {
    public DevicePhoneTierRewardUpdateRequest(
            BigDecimal dailyUsdt, BigDecimal dailyNex, String reason, String operator) {
        this(dailyUsdt, dailyNex, 1L, reason, operator);
    }
}
