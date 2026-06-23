package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DevicePhoneTierRewardUpdateRequest(
        BigDecimal dailyUsdt,
        BigDecimal dailyNex,
        String reason,
        String operator) {
}
