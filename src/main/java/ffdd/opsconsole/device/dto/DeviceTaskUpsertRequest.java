package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DeviceTaskUpsertRequest(
        String name,
        BigDecimal price,
        String unit,
        String requirement,
        BigDecimal saturation,
        String status,
        String reason,
        String operator) {
}
