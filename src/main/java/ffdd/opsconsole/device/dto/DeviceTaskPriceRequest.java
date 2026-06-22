package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DeviceTaskPriceRequest(
        BigDecimal price,
        String reason,
        String operator) {
}
