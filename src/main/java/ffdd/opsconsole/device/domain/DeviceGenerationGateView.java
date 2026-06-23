package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceGenerationGateView(
        String id,
        String name,
        Integer releaseMonth,
        String phase,
        BigDecimal discount,
        Boolean eligibility,
        Integer phaseOffset,
        Boolean forceUnlock,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
