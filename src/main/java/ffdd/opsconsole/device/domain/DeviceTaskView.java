package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceTaskView(
        String taskId,
        String name,
        BigDecimal price,
        String unit,
        String requirement,
        BigDecimal saturation,
        String status,
        String taskClass,
        String model,
        BigDecimal minReward,
        BigDecimal maxReward,
        String minVram,
        String killInit,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
