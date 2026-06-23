package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DeviceTaskUpsertRequest(
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
        String reason,
        String operator) {
}
