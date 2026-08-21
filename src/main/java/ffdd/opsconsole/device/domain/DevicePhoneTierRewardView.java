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
        LocalDateTime updatedAt,
        Long revision) {
    public DevicePhoneTierRewardView(
            Integer tier, String name, String note, BigDecimal dailyUsdt, BigDecimal dailyNex,
            String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(tier, name, note, dailyUsdt, dailyNex, status, createdAt, updatedAt, 1L);
    }
}
