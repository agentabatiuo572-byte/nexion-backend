package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DevicePhoneTierRewardView(
        Integer tier,
        String name,
        String note,
        BigDecimal dailyUsdt,
        BigDecimal dailyNex,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
