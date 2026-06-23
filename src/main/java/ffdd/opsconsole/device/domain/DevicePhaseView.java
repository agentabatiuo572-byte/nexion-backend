package ffdd.opsconsole.device.domain;

import java.time.LocalDateTime;

public record DevicePhaseView(
        String p,
        String label,
        String meta,
        String skus,
        Integer sortOrder,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
