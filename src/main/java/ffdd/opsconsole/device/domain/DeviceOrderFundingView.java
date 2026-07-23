package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceOrderFundingView(
        String source,
        String bizNo,
        String status,
        String direction,
        BigDecimal amount,
        LocalDateTime occurredAt) {
}
