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
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
