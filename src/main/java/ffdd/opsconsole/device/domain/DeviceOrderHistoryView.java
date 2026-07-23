package ffdd.opsconsole.device.domain;

import java.time.LocalDateTime;

public record DeviceOrderHistoryView(
        String fromState,
        String toState,
        String reason,
        String operator,
        LocalDateTime createdAt) {
}
